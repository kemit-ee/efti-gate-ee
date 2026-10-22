package edelivery

import klite.*
import klite.StatusCode.Companion.InternalServerError
import klite.StatusCode.Companion.OK
import klite.StatusCode.Companion.ServiceUnavailable
import java.util.UUID
import klite.annotations.GET
import klite.annotations.POST
import klite.xml.XmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.Thread.currentThread
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.util.HexFormat
import java.util.UUID.randomUUID
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

const val soap = "application/soap+xml"

class EDeliveryRoutes(
  private val keyManager: KeyManager,
  private val messageHandlers: MessageHandlers,
  private val eDeliveryMessageGenerator: EDeliveryMessageGenerator,
  private val eDeliveryClient: EDeliveryClient,
  private val partyRegistry: PartyRegistry,
  private val signatureValidator: SignatureValidator,
  private val turvisInbound: TurvisInbound,
  private val turvisShadow: TurvisShadow,
) {
  private val rootTagRegex = "<\\s*(?:\\w+:)?(\\w+)".toRegex()

  private val xmlParser = XmlParser()
  private val log = logger()

  @GET("/msh") fun mshInfo() = "eDelivery MSH endpoint is up, please send POST requests"

  @POST("/msh")
  fun msh(e: HttpExchange) {
    val bodyBytes = e.requestStream.readBytes()
    val auditRef = randomUUID().toString()
    if (turvisInbound.authoritative) return mshViaTurvis(e, bodyBytes, auditRef)
    var partCount: Int? = null
    val observed = LegacyObservation()
    try {
      val body = MultipartParser().parse(bodyBytes.inputStream())
      partCount = body.size
      val xml = body.values.first() as String
      val encryptedPayload = body.values.last() as ByteArray

      val header = xmlParser.parse<MessageHeader>(xml)
      currentThread().name = header.conversationId.toString()

      if (header.receiverId != keyManager.partyId) log.warn("Unknown receiver: ${header.receiverId}")
      observed.stage = LegacyObservation.Stage.PARTY_LOOKUP
      val party = partyRegistry[header.senderId]
      e.attr("client", party.id)
      observed.stage = LegacyObservation.Stage.CRYPTO
      observed.senderId = header.senderId
      observed.receiverId = header.receiverId
      observed.conversationId = header.conversationId.toString()
      observed.messageId = header.messageId

      val encryptedSymmetricKey = header.cipherValue?.trim()
        ?.takeIf { it.isNotBlank() }?.base64Decode()
        ?: body.values.toList().getOrNull(1) as ByteArray
      val decrypted = decryptPayload(header, keyManager.ownPrivateKey, encryptedPayload, encryptedSymmetricKey)
      observed.attachment = decrypted.attachmentBytes
      signatureValidator.verify(xml, header, keyManager.receiverCert(header.senderId), decrypted.attachmentBytes)
      observed.stage = LegacyObservation.Stage.VERIFIED
      val payloadXml = decrypted.xml
      observed.payloadXml = payloadXml

      val rootTag = rootTagRegex.from(payloadXml)
      val responseKey = RequestKey(header.senderId, header.conversationId, header.receiverId)
      val handler = messageHandlers.rootTags[rootTag] ?: throw UnsupportedOperationException("Unsupported message '$rootTag' from $responseKey")
      EDeliveryMetrics.inbound(rootTag)
      log.info("Handling '$rootTag' from $responseKey")

      val responseXml = eDeliveryMessageGenerator.responseMessage(header)
      e.send(OK, responseXml, soap)
      turvisShadow.compareInbound(auditRef, bodyBytes, LegacyDecision.Accepted(observed))

      AppScope.async {
        val result = handler(MessageContext(responseKey, payloadXml))
        if (result != null)
          eDeliveryClient.send(party.eDeliveryUrl, UserMessageParams(responseKey), result)
      }
    } catch (ex: Exception) {
      val reason = FaultReason.of(ex)
      log.error("Error when processing message: audit_ref=$auditRef reason=$reason body_sha256=${bodyBytes.sha256Hex()} " +
        "body_size_bytes=${bodyBytes.size} content_type=${e.header("Content-Type")} multipart_part_count=$partCount", ex.withoutMessages())
      e.send(InternalServerError, eDeliveryMessageGenerator.soapFault(reason), soap)
      turvisShadow.compareInbound(auditRef, bodyBytes, legacyDecision(observed, reason))
    }
  }

  /** Verification passed in the legacy path even if a later step (unsupported root tag, handler lookup) failed: that is not a security decision. */
  private fun legacyDecision(observed: LegacyObservation, reason: FaultReason) = when (observed.stage) {
    LegacyObservation.Stage.VERIFIED -> LegacyDecision.Accepted(observed)
    LegacyObservation.Stage.PARTY_LOOKUP -> LegacyDecision.NotComparable
    else -> LegacyDecision.Rejected(reason, observed.senderId)
  }

  /**
   * Inbound with Turvis as the authority (`TURVIS_INTEGRATION_MODE=turvis`, SPEC 2.2/9): no XML is parsed here,
   * routing and the sender come from Turvis's verify response, nothing is dispatched before verify succeeds.
   *
   * NOT COMPLETE until 305c: the receipt (the signed response to the peer) is built and signed in-process today,
   * and the in-process path is exactly what this mode must not call, so this answers 200 without a receipt.
   * The mode stays off by default and is not to be switched on before 305c.
   */
  private fun mshViaTurvis(e: HttpExchange, bodyBytes: ByteArray, auditRef: String) {
    try {
      val inbound = turvisInbound.process(bodyBytes)
      val routing = inbound.routing
      val conversationId = routing.conversationId?.let(UUID::fromString) ?: throw IllegalArgumentException("Message has no conversation id")
      currentThread().name = conversationId.toString()

      val receiverId = PartyId(routing.toPartyId)
      if (receiverId != keyManager.partyId) log.warn("Unknown receiver: $receiverId")
      val senderId = PartyId(inbound.signer.verifiedIdentity) // the signature's identity, never routing.fromPartyId
      val party = partyRegistry[senderId]
      e.attr("client", party.id)

      val rootTag = rootTagRegex.from(inbound.payloadXml)
      val responseKey = RequestKey(senderId, conversationId, receiverId)
      val handler = messageHandlers.rootTags[rootTag] ?: throw UnsupportedOperationException("Unsupported message '$rootTag' from $responseKey")
      EDeliveryMetrics.inbound(rootTag)
      log.info("Handling '$rootTag' from $responseKey (turvis decrypt=${inbound.decryptAuditRef} verify=${inbound.verifyAuditRef})")

      e.send(OK, "", soap)

      AppScope.async {
        val result = handler(MessageContext(responseKey, inbound.payloadXml))
        if (result != null)
          eDeliveryClient.send(party.eDeliveryUrl, UserMessageParams(responseKey), result)
      }
    } catch (ex: Exception) {
      // Internal, transient or unreachable Turvis is not the peer's fault: HTTP 503 and no ebMS fault (SPEC 13.2).
      val noFault = when (ex) {
        is TurvisUnavailableException, is TurvisProtocolException, is TurvisAuthException -> true
        is TurvisRejectException -> FaultReason.ofTurvis(ex.rejection.effectiveReason) == null
        else -> false
      }
      val reason = if (noFault) "TURVIS_UNAVAILABLE" else FaultReason.of(ex).name
      val turvisAuditRef = (ex as? TurvisRejectException)?.auditRef
      log.error("Error when processing message via turvis: audit_ref=$auditRef reason=$reason turvis_audit_ref=$turvisAuditRef " +
        "body_sha256=${bodyBytes.sha256Hex()} body_size_bytes=${bodyBytes.size} content_type=${e.header("Content-Type")}", ex.withoutMessages())
      if (noFault) e.send(ServiceUnavailable, "", soap)
      else e.send(InternalServerError, eDeliveryMessageGenerator.soapFault(FaultReason.of(ex)), soap)
    }
  }

  private data class DecryptedPayload(val attachmentBytes: ByteArray, val xml: String)

  private fun decryptPayload(header: MessageHeader, privateKey: PrivateKey, encryptedPayload: ByteArray, encryptedSymmetricKey: ByteArray): DecryptedPayload {
    val incomingIdentifier = header.keyIdentifier ?: header.serialNumber
    require(incomingIdentifier != null) {
      "No valid KeyIdentifier or X509SerialNumber found in the message header."
    }

    if (header.keyIdentifier != null) {
      require(keyManager.ownCertSki == header.keyIdentifier) {
        "Invalid KeyIdentifier \"${header.keyIdentifier}\", expected \"${keyManager.ownCertSki}\""
      }
    } else {
      require(keyManager.ownCertSerialNumber == header.serialNumber) {
        "Invalid X509SerialNumber \"${header.serialNumber}\", expected \"${keyManager.ownCertSerialNumber}\""
      }
    }

    if (header.keyEncryptionAlgorithm != "http://www.w3.org/2009/xmlenc11#rsa-oaep")
      log.warn("Unknown key encryption method: ${header.keyEncryptionAlgorithm}")
    if (header.dataEncryptionAlgorithm != "http://www.w3.org/2009/xmlenc11#aes128-gcm")
      log.warn("Unknown data encryption method: ${header.dataEncryptionAlgorithm}")

    val oaepSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
    val cipherRSA = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
    cipherRSA.init(Cipher.DECRYPT_MODE, privateKey, oaepSpec)
    val aesKeyBytes = cipherRSA.doFinal(encryptedSymmetricKey)
    val secretKey = SecretKeySpec(aesKeyBytes, "AES")

    val iv = encryptedPayload.sliceArray(0 until 12)
    val ciphertext = encryptedPayload.sliceArray(12 until encryptedPayload.size)

    val gcmSpec = GCMParameterSpec(128, iv)
    val cipherAES = Cipher.getInstance("AES/GCM/NoPadding")
    cipherAES.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
    val decryptedBytes = cipherAES.doFinal(ciphertext)

    val xml = when (header.compressionType) {
      "application/gzip" -> String(gunzip(decryptedBytes))
      else -> String(decryptedBytes)
    }
    return DecryptedPayload(decryptedBytes, xml)
  }
}

private fun ByteArray.sha256Hex() = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this))

internal fun gunzip(compressed: ByteArray): ByteArray {
  val maxBytes = Config.optional("BODY_LIMIT_MD", "10").toLong() * 1024 * 1024

  GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
      val read = gzip.read(buffer)
      if (read < 0) break
      total += read
      if (total > maxBytes) throw PayloadTooLargeException("Decompressed payload exceeds the $maxBytes byte limit")
      out.write(buffer, 0, read)
    }
    return out.toByteArray()
  }
}

fun Regex.from(s: String, n: Int = 1): String? = find(s)?.groups[n]?.value
