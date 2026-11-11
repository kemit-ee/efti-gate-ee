package edelivery

import io.mockk.*
import klite.HttpExchange
import klite.StatusCode.Companion.OK
import klite.base64Decode
import klite.base64Encode
import klite.xml.XmlParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Legacy mode: for every eFTI message type the receipt that `EDeliveryRoutes.msh` returns is verified in-process.
 *
 * The sender (peer) signs with its own key A, generated here with keytool; the gate signs the receipt with its own key B
 * (the test keystore). So the receipt must verify with B and must not verify with A.
 * Limits: this proves the receipt is signed by the gate's key and acknowledges exactly the references the peer signed.
 * It does not prove that an external peer resolves our certificate by SKI (M0d, #392).
 */
class ReceiptVerificationTest {
  @TempDir lateinit var dir: Path

  private val peerId = PartyId("fi-peer")
  private val xmlParser = XmlParser()
  private val validator = SignatureValidator()

  private val gateKeys = spyk(KeyManager(mockk<PartyRegistry>(relaxUnitFun = true)))
  private val peerKeyStore by lazy { generatePeerKey() }
  private val peerCert by lazy { peerKeyStore.getCertificate("s") as X509Certificate }
  private val peerKey by lazy { peerKeyStore.getKey("s", "changeit".toCharArray()) as PrivateKey }

  private fun generatePeerKey(): KeyStore {
    val keytool = File(System.getProperty("java.home"), "bin/keytool").path
    val store = dir.resolve("peer.p12").toFile()
    val code = ProcessBuilder(keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=peer",
      "-validity", "2", "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "changeit")
      .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
    check(code == 0) { "keytool failed" }
    return KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "changeit".toCharArray()) } }
  }

  private val peerKeys by lazy {
    spyk(KeyManager(mockk<PartyRegistry>(relaxUnitFun = true))).also {
      every { it.ownPrivateKey } returns peerKey
      every { it.ownCert } returns peerCert
      every { it.ownCertSki } returns it.certSki(peerCert)
      every { it.ownCertSerialNumber } returns peerCert.serialNumber.toString()
      every { it.partyId } returns peerId
      every { it.receiverCert(any()) } returns gateKeys.ownCert
    }
  }

  private class Run(val input: String, val receipt: String)

  private fun exchange(rootTag: String): Run {
    every { gateKeys.receiverCert(peerId) } returns peerCert
    val partyRegistry = mockk<PartyRegistry>(relaxUnitFun = true) {
      every { get(peerId) } returns EDeliveryParty(peerId, URI("http://peer"), "")
    }
    val handled = java.util.concurrent.CopyOnWriteArrayList<String>()
    val handler: (MessageContext) -> String? = { handled += it.xml; null }
    val handlers = object : MessageHandlers { override val rootTags = mapOf(rootTag to handler) }
    val legacyInbound = TurvisInbound(TurvisConfig(keyHandle = "EU-EE"), mockk(relaxed = true))
    val routes = EDeliveryRoutes(gateKeys, handlers, EDeliveryMessageGenerator(gateKeys), mockk(relaxed = true), partyRegistry, validator,
      legacyInbound, TurvisShadow(TurvisConfig(keyHandle = "EU-EE"), legacyInbound), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    val payload = "<$rootTag><x>1</x></$rootTag>"
    val (xml, encryptedPayload) = EDeliveryMessageGenerator(peerKeys).requestMessage(UserMessageParams(RequestKey(gateKeys.partyId, senderId = peerId)), payload)
    val boundary = "----boundary"
    val body = listOf(
      "--$boundary\r\nContent-Type: $soap\r\n\r\n".toByteArray(), xml.toByteArray(), "\r\n".toByteArray(),
      "--$boundary\r\nContent-Type: application/octet-stream\r\nContent-ID: <message>\r\n\r\n".toByteArray(), encryptedPayload, "\r\n--$boundary--".toByteArray(),
    ).reduce(ByteArray::plus)

    val exchange = mockk<HttpExchange>(relaxed = true) { every { requestStream } returns body.inputStream() }
    routes.msh(exchange)
    val sent = slot<String>()
    verify { exchange.send(OK, capture(sent), soap) }
    val deadline = System.currentTimeMillis() + 5000
    while (handled.none { it.contains(payload) } && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assertTrue(handled.any { it.contains(payload) }, "handler for $rootTag was not called")
    return Run(xml, sent.captured)
  }

  private fun digests(xml: String, section: String? = null): List<String> {
    val scope = section?.let { xml.substringAfter("<$it").substringBefore("</$it>") } ?: xml
    return Regex("<ds:DigestValue>([^<]*)</ds:DigestValue>").findAll(scope).map { it.groupValues[1] }.toList()
  }

  @ParameterizedTest @MethodSource("rootTags")
  fun `receipt is signed by the gate key and acknowledges the peer's signed references`(rootTag: String) {
    val run = exchange(rootTag)
    val inputHeader = xmlParser.parse<MessageHeader>(run.input)

    assertDoesNotThrow { validator.verify(run.receipt, inputHeader, gateKeys.ownCert, ByteArray(0)) }
    assertThrows<SecurityException>("must not verify with the sender's key") { validator.verify(run.receipt, inputHeader, peerCert, ByteArray(0)) }

    assertTrue(run.receipt.contains("<eb:RefToMessageId>${inputHeader.messageId}</eb:RefToMessageId>"), run.receipt)
    val acknowledged = digests(run.receipt, "ebbp:NonRepudiationInformation")
    assertEquals(3, acknowledged.size)
    assertEquals(inputHeader.references.map { it.digestValue }, acknowledged)
  }

  @Test fun `the parameterized root tags are exactly the production handler tags`() {
    val production = Class.forName("EftiMessageHandlers").constructors.single().newInstance(mockk<AsyncResponseProvider>(), mockkClass(Class.forName("RuuterClient").kotlin)) as MessageHandlers
    assertEquals(production.rootTags.keys - "hello", rootTags().toSet())
  }

  @Test fun `receipt with a changed NRR DigestValue does not verify`() {
    val run = exchange("FTI009GetCmdsRequest")
    val header = xmlParser.parse<MessageHeader>(run.input)
    val original = digests(run.receipt, "ebbp:NonRepudiationInformation").first()
    val flipped = original.base64Decode().also { it[0] = (it[0] + 1).toByte() }.base64Encode()
    assertNotEquals(original, flipped)
    val tampered = run.receipt.replaceFirst("<ds:DigestValue>$original</ds:DigestValue>", "<ds:DigestValue>$flipped</ds:DigestValue>")
    assertThrows<SecurityException> { validator.verify(tampered, header, gateKeys.ownCert, ByteArray(0)) }
  }

  @Test fun `receipt with a damaged SignatureValue does not verify`() {
    val run = exchange("FTI009GetCmdsRequest")
    val header = xmlParser.parse<MessageHeader>(run.input)
    val match = Regex("<ds:SignatureValue>([^<]+)</ds:SignatureValue>").find(run.receipt)!!
    val damaged = match.groupValues[1].base64Decode().also { it[0] = (it[0] + 1).toByte() }.base64Encode()
    assertThrows<SecurityException> { validator.verify(run.receipt.replace(match.groupValues[1], damaged), header, gateKeys.ownCert, ByteArray(0)) }
  }

  companion object {
    @JvmStatic fun rootTags() = listOf(
      "FTI004UploadIdentifierRequest", "FTI009GetCmdsRequest", "FTI010GetCmdsResponse", "FTI019SearchIdentifierRequest",
      "FTI021SearchIdentifierResponse", "FTI025LodgeFollowUpCommRequest", "FTI029UploadIdentifierResponse", "FTI030LodgeFollowUpCommResponse",
    )
  }
}
