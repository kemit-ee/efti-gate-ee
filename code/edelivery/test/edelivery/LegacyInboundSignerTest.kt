package edelivery

import io.mockk.*
import klite.HttpExchange
import klite.StatusCode.Companion.InternalServerError
import klite.StatusCode.Companion.OK
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate

/** Legacy inbound: a message signed with a key other than the certificate registered for its sender gets no receipt and is not dispatched. */
class LegacyInboundSignerTest {
  @TempDir lateinit var dir: Path

  private val party = EDeliveryParty(PartyId("fi-tst"), URI("http://ee"), "")
  private val partyRegistry = mockk<PartyRegistry>(relaxUnitFun = true) { every { get(party.id) } returns party }
  private val keyManager = spyk(KeyManager(partyRegistry))
  private val generator = EDeliveryMessageGenerator(keyManager)

  private fun registeredCertOfSomeoneElse(): X509Certificate {
    val keytool = File(System.getProperty("java.home"), "bin/keytool").path
    val store = dir.resolve("other.p12").toFile()
    val code = ProcessBuilder(keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=other",
      "-validity", "2", "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "changeit")
      .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
    check(code == 0) { "keytool failed" }
    return KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "changeit".toCharArray()) } }.getCertificate("s") as X509Certificate
  }

  @Test fun `a message signed by a key that is not the registered one of its sender is refused without receipt or dispatch`() {
    every { keyManager.receiverCert(any()) } returns keyManager.ownCert
    val payload = "<hello>world</hello>"
    val (xml, encryptedPayload) = generator.requestMessage(UserMessageParams(RequestKey(party.id, senderId = party.id)), payload)
    every { keyManager.receiverCert(party.id) } returns registeredCertOfSomeoneElse()
    val boundary = "----boundary"
    val body = listOf(
      "--$boundary\r\nContent-Type: $soap\r\n\r\n".toByteArray(), xml.toByteArray(), "\r\n".toByteArray(),
      "--$boundary\r\nContent-Type: application/octet-stream\r\nContent-ID: <message>\r\n\r\n".toByteArray(), encryptedPayload, "\r\n--$boundary--".toByteArray(),
    ).reduce(ByteArray::plus)

    val handler = mockk<(MessageContext) -> String?>(relaxed = true)
    val handlers = mockk<MessageHandlers> { every { rootTags } returns mapOf("hello" to handler) }
    val legacyInbound = TurvisInbound(TurvisConfig(keyHandle = "EU-EE"), mockk(relaxed = true))
    val routes = EDeliveryRoutes(keyManager, handlers, generator, mockk(relaxed = true), partyRegistry, SignatureValidator(), legacyInbound,
      TurvisShadow(TurvisConfig(keyHandle = "EU-EE"), legacyInbound), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    val exchange = mockk<HttpExchange>(relaxed = true) { every { requestStream } returns body.inputStream() }

    routes.msh(exchange)

    val fault = slot<String>()
    verify(exactly = 1) { exchange.send(InternalServerError, capture(fault), soap) }
    assertTrue(fault.captured.contains("EBMS:0101"), fault.captured)
    verify(exactly = 0) { exchange.send(OK, any<String>(), any()) }
    Thread.sleep(300)
    verify(exactly = 0) { handler.invoke(any()) }
  }
}
