package core

import klite.*
import klite.StatusCode.Companion.InternalServerError
import klite.StatusCode.Companion.OK
import klite.annotations.GET
import klite.annotations.HeaderParam
import klite.annotations.POST
import klite.xml.XmlParser
import java.io.ByteArrayInputStream
import java.lang.Thread.currentThread
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

const val soap = "application/soap+xml"

class EDeliveryRoutes(
  private val keyManager: KeyManager,
  private val messageHandler: MessageHandler,
  private val eDeliveryMessageGenerator: EDeliveryMessageGenerator,
  private val eDeliveryClient: EDeliveryClient,
  private val partyRegistry: PartyRegistry
) {
  private val messagesReceived = AtomicLong().also {
    Metrics.register("edelivery_messages_received") { it.get() }
  }

  private val xmlParser = XmlParser()
  private val log = logger()

  // TODO: drop regexps
  private val requestIdRegex = """requestId="([^"]+)"""".toRegex()
  private val ftiRequestIdRegex = """<ExchangedDocument>\s*<ID>([^<]+)</ID>""".toRegex()

  @GET("/msh") fun mshInfo() = "eDelivery MSH endpoint is up, please send POST requests"

  @POST("/msh")
  fun msh(e: HttpExchange) {
    val bodyBytes = e.requestStream.readBytes()
    try {
      messagesReceived.incrementAndGet()
      val body = MultipartParser().parse(bodyBytes.inputStream())
      val xml = body.values.first() as String
      val encryptedPayload = body.values.last() as ByteArray

      val header = xmlParser.parse<MessageHeader>(xml)
      if (header.receiverId != Config.partyId) log.warn("Unknown receiver: ${header.receiverId}")
      e.attr("client", header.senderId)

      val encryptedSymmetricKey = header.cipherValue?.trim()
        ?.takeIf { it.isNotBlank() }?.base64Decode()
        ?: body.values.toList().getOrNull(1) as ByteArray
      val payloadXml = decryptPayload(header, keyManager.ownPrivateKey, encryptedPayload, encryptedSymmetricKey)

      val thread = currentThread()
      val extRequestId = requestIdRegex.from(payloadXml) ?: ftiRequestIdRegex.from(payloadXml)
      if (extRequestId != null) thread.name = "${e.requestId}/$extRequestId"

      val responseXml = eDeliveryMessageGenerator.responseMessage(header)
      e.send(OK, responseXml, soap)

      AppScope.async {
        val responseKey = RequestKey(header.senderId, extRequestId ?: e.requestId, header.receiverId)
        val party = partyRegistry[responseKey.receiverId]
        val result = messageHandler.response(responseKey, payloadXml)
        if (result != null)
          eDeliveryClient.send(party.eDeliveryUrl, UserMessageParams(responseKey), result)
      }
    } catch (ex: Exception) {
      log.error("Error when processing message: ${ex.message}. Raw content: ${bodyBytes.decodeToString()}", ex)
      e.send(InternalServerError,
        eDeliveryMessageGenerator.soapFault("Failed to process eDelivery message", ex),
        soap)
    }
  }

  @POST("/fast")
  fun fast(payload: String, e: HttpExchange, @HeaderParam("X-API-Key") apiKey: String) {
    // TODO: review API key security
    val requestId = requestIdRegex.from(payload) ?: ftiRequestIdRegex.from(payload)
    val response = messageHandler.response(RequestKey(PartyId(apiKey), requestId ?: e.requestId), payload)
    e.send(OK, response, MimeTypes.xml)
  }

  private fun decryptPayload(header: MessageHeader, privateKey: PrivateKey, encryptedPayload: ByteArray, encryptedSymmetricKey: ByteArray): String {
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

    // TODO: check for CompressionType in the message to decide whether to decompress
    return String(GZIPInputStream(ByteArrayInputStream(decryptedBytes)).readAllBytes())
  }
}

fun Regex.from(s: String, n: Int = 1): String? = find(s)?.groups[n]?.value
