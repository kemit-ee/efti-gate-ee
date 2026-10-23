package edelivery

import klite.*
import klite.StatusCode.Companion.BadRequest
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
  private val turvisOutbound: TurvisOutbound,
  private val asyncFailureAudit: AsyncFailureAudit,
  private val inboundDedup: InboundDedup,
) {
  private val rootTagRegex = "<\\s*(?:\\w+:)?(\\w+)".toRegex()

  /** How a handler is run after the peer has been answered; tests replace it with a synchronous one to assert dispatch or its absence deterministically. */
  internal var dispatch: (auditRef: String, context: Map<String, String>, block: () -> Unit) -> Unit =
    { auditRef, context, block -> auditAsync(asyncFailureAudit, auditRef, "handler", context, block) }

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
      val parts = body.values.toList()
      if (parts.size < 2) throw MalformedMultipartException()
      val xml = parts.first() as? String ?: throw MalformedMultipartException()
      val encryptedPayload = parts.last() as? ByteArray ?: throw MalformedMultipartException()

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
        ?: parts[1] as? ByteArray ?: throw MalformedMultipartException()
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

      dispatch(auditRef, mapOf("response_key" to responseKey.toString())) {
        val result = handler(MessageContext(responseKey, payloadXml))
        if (result != null)
          eDeliveryClient.send(party.eDeliveryUrl, UserMessageParams(responseKey), result)
      }
    } catch (ex: Exception) {
      val reason = FaultReason.of(ex)
      log.error("Error when processing message: audit_ref=$auditRef reason=$reason body_sha256=${bodyBytes.sha256Hex()} " +
        "body_size_bytes=${bodyBytes.size} content_type=${e.header("Content-Type")} multipart_part_count=$partCount", ex.withoutMessages())
      e.send(if (ex is MalformedMultipartException) BadRequest else InternalServerError, eDeliveryMessageGenerator.soapFault(reason), soap)
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
   * The receipt (NRR response) is built from the references Turvis's verify reported and signed by Turvis, before
   * anything is dispatched; if that fails the peer gets HTTP 503 and the message is not handled (it will retry).
   * Still not to be switched on before real-service interoperability (#308): the mock proves shapes, not crypto.
   */
  private fun mshViaTurvis(e: HttpExchange, bodyBytes: ByteArray, auditRef: String) {
    var recordedAndAnswered = false
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

      val payloadSha256 = inbound.payloadXml.toByteArray().sha256Hex()
      // A repeat of a message already accepted from this verified sender gets the stored receipt and is not dispatched again (#336).
      inboundDedup.find(senderId, routing.messageId)?.let { return answerDuplicate(e, auditRef, inbound, it, payloadSha256) }

      val receipt = try {
        turvisOutbound.receipt(routing.messageId, inbound.signatureDigests, conversationId)
      } catch (ex: Exception) {
        log.info(TurvisAudit.inbound(auditRef, inbound, null))
        // Not signing OUR receipt is never the peer's fault: 503 without a fault, so the peer retries (SPEC 13.2).
        // The exact reason is already counted and logged inside receipt().
        throw TurvisUnavailableException("receipt not signed", ex)
      }
      // Recorded before the answer and the dispatch: a store failure is a 503 (nothing recorded, nothing dispatched, the peer retries);
      // losing the race to another node means that node dispatches, so this one answers like for any repeat.
      val recorded = try {
        inboundDedup.record(senderId, routing.messageId, payloadSha256, receipt.signedSoap)
      } catch (ex: InboundDedupUnavailableException) {
        // The INSERT may have committed and only its answer been lost. The stored receipt decides: our own (its MessageId is random per
        // attempt) means this call won and goes on; another one means a repeat; no answer from the store at all stays a 503.
        val stored = try { inboundDedup.find(senderId, routing.messageId) } catch (_: InboundDedupUnavailableException) { null } ?: throw ex
        if (stored.receipt.contentEquals(receipt.signedSoap)) true else return answerDuplicate(e, auditRef, inbound, stored, payloadSha256)
      }
      if (!recorded) {
        val winner = inboundDedup.find(senderId, routing.messageId) ?: throw InboundDedupUnavailableException()
        return answerDuplicate(e, auditRef, inbound, winner, payloadSha256)
      }
      // From here on the message counts as accepted: a peer that did not get the receipt repeats it and is answered from the store,
      // so the dispatch must happen even if this answer cannot be written.
      try {
        e.send(OK, receipt.signedSoap, soap)
      } catch (ex: Exception) {
        log.warn("Receipt not written to the peer, the message is dispatched and a repeat is answered from the store: audit_ref=$auditRef", ex.withoutMessages())
      }
      recordedAndAnswered = true
      log.info(TurvisAudit.inbound(auditRef, inbound, receipt.signAuditRef))

      dispatch(auditRef, mapOf("response_key" to responseKey.toString())) {
        val result = handler(MessageContext(responseKey, inbound.payloadXml))
        if (result != null)
          eDeliveryClient.send(party.eDeliveryUrl, UserMessageParams(responseKey), result)
      }
    } catch (ex: Exception) {
      if (recordedAndAnswered) {
        // The message is recorded and the peer has its receipt: a second answer would only fail, and a repeat is answered from the store.
        EDeliveryMetrics.asyncHandlerFailure(FaultReason.of(ex).name)
        log.error("Dispatch could not be started after the receipt: audit_ref=$auditRef", ex.withoutMessages())
        return
      }
      // Internal, transient or unreachable Turvis is not the peer's fault: HTTP 503 and no ebMS fault (SPEC 13.2).
      val noFault = when (ex) {
        is TurvisUnavailableException, is TurvisProtocolException, is TurvisAuthException, is InboundDedupUnavailableException -> true
        is TurvisRejectException -> FaultReason.ofTurvis(ex.rejection.effectiveReason) == null
        else -> false
      }
      val dedupDown = ex is InboundDedupUnavailableException
      if (dedupDown) EDeliveryMetrics.inboundDedupUnavailable()
      val reason = if (dedupDown) "DEDUP_UNAVAILABLE" else if (noFault) "TURVIS_UNAVAILABLE" else FaultReason.of(ex).name
      val turvisAuditRef = (ex as? TurvisRejectException)?.auditRef
      log.error("Error when processing message via turvis: audit_ref=$auditRef reason=$reason turvis_audit_ref=$turvisAuditRef " +
        "body_sha256=${bodyBytes.sha256Hex()} body_size_bytes=${bodyBytes.size} content_type=${e.header("Content-Type")}", ex.withoutMessages())
      if (noFault) e.send(ServiceUnavailable, "", soap)
      else e.send(InternalServerError, eDeliveryMessageGenerator.soapFault(FaultReason.of(ex)), soap)
    }
  }

  /**
   * The stored receipt goes back byte for byte (not a newly signed one: that would be a second receipt, with another MessageId, for one message)
   * when the repeat carries the same payload; a repeat with another payload gets a SOAP fault (`FaultReason.OTHER`, ebMS EBMS:0004) and is not dispatched.
   */
  private fun answerDuplicate(e: HttpExchange, auditRef: String, inbound: VerifiedInbound, stored: AcceptedInbound, payloadSha256: String) {
    val samePayload = stored.payloadSha256 == payloadSha256
    EDeliveryMetrics.inboundDuplicate(samePayload)
    log.warn(TurvisAudit.duplicate(auditRef, inbound, samePayload))
    try {
      // Same id, other content: a receipt would claim we accepted what we did not (its references are the original's), so the peer gets a fault.
      if (samePayload) e.send(OK, stored.receipt, soap)
      else e.send(InternalServerError, eDeliveryMessageGenerator.soapFault(FaultReason.OTHER), soap)
    } catch (ex: Exception) {
      log.warn("Answer to a repeated message not written to the peer: audit_ref=$auditRef", ex.withoutMessages())
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

/** The legacy path's decompression cap; the shadow compares the Turvis budget against it. */
internal fun legacyPayloadLimit(): Long = Config.optional("BODY_LIMIT_MD", "10").toLong() * 1024 * 1024

internal fun gunzip(compressed: ByteArray, maxBytes: Long = legacyPayloadLimit()): ByteArray {
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
