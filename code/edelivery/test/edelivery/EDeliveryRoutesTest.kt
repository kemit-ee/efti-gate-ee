package edelivery

import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.*
import klite.HttpExchange
import klite.MultipartParser
import klite.StatusCode.Companion.InternalServerError
import klite.StatusCode.Companion.OK
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EDeliveryRoutesTest {
  val party = EDeliveryParty(PartyId("fi-tst"), URI("http://ee"), "")
  val partyRegistry = mockk<PartyRegistry>(relaxUnitFun = true) {
    every { get(party.id) } returns party
  }
  val mockHandler = mockk<(MessageContext) -> String?>()
  val keyManager = spyk(KeyManager(partyRegistry))
  val messageHandlers = mockk<MessageHandlers>()
  val realMessageGenerator = EDeliveryMessageGenerator(keyManager)
  val messageGenerator = mockk<EDeliveryMessageGenerator>(relaxed = true)
  val eDeliveryClient = mockk<EDeliveryClient>(relaxed = true)
  val exchange = mockk<HttpExchange>(relaxed = true)

  val routes = EDeliveryRoutes(keyManager, messageHandlers, messageGenerator, eDeliveryClient, partyRegistry)

  fun getEnvelope(cipherValue: String): String {
    val regex = Regex("(<xenc:CipherValue\\b[^>]*>)(.*?)(</xenc:CipherValue>)", setOf(RegexOption.DOT_MATCHES_ALL))

    val xml = File("test/edelivery/soap-env.xml").readText()

    return regex.replace(xml) { it.groupValues[1] + cipherValue + it.groupValues[3] }
  }

  fun getBody(payload: String): ByteArray {
    val aesKey = realMessageGenerator.generateAESKey()

    val mimeBoundary = "----boundary"
    return listOf(
      "--$mimeBoundary\r\nContent-Type: $soap\r\n\r\n".toByteArray(),
      getEnvelope("""<xop:Include href="cid:7c232fbd-2b4d-4048-97c4-58dfa4fce251" />""").toByteArray(),
      ("\r\n--$mimeBoundary\r\n" +
        "Content-Type: application/ciphervalue\r\n" +
        "Content-ID: <7c232fbd-2b4d-4048-97c4-58dfa4fce251>\r\n\r\n").toByteArray(),
      realMessageGenerator.encryptAesKey(keyManager.ownCert, aesKey),
      ("\r\n--$mimeBoundary\r\n" +
        "Content-Type: application/octet-stream\r\n" +
        "Content-ID: <message>\r\n\r\n").toByteArray(),
      realMessageGenerator.encryptPayload(aesKey, realMessageGenerator.gzip(payload.toByteArray())),
      "\r\n--$mimeBoundary--".toByteArray()
    ).reduce(ByteArray::plus)
  }

  @Test fun encryptedAesKeyInAnotherPart() {
    val payload = "<hello>world</hello>"
    val body = getBody(payload)

    every { exchange.requestStream } returns body.inputStream()

    every { mockHandler.invoke(any()) } returns "mockedResponse"
    every { messageHandlers.rootTags } returns mapOf("hello" to mockHandler)

    routes.msh(exchange)

    verify {
      mockHandler.invoke(match {
        it.key.receiverId == party.id && it.xml.contains(payload)
      })
    }
  }

  @Test fun `Unknown root tag gives SOAP error`() {
    val payload = "<unknown-root-tag>unknown</unknown-root-tag>"
    val body = getBody(payload)

    every { exchange.requestStream } returns body.inputStream()

    every { messageHandlers.rootTags[any()] } returns null
    every { messageGenerator.soapFault(any(), any()) } returns "<SOAP-ENV:Fault/>"

    routes.msh(exchange)

    verify {
      exchange.send(InternalServerError, match<String> { it.contains("Fault", ignoreCase = true) }, soap)
    }
  }

  @Test fun `msh fails with invalid body`() {
    mockkConstructor(MultipartParser::class)
    every { anyConstructed<MultipartParser>().parse(any()) } returns emptyMap()
    every { exchange.requestStream } returns "invalid".toByteArray().inputStream()
    every { messageGenerator.soapFault(any(), any()) } returns "<fault/>"

    try {
      routes.msh(exchange)
    } finally {
      unmockkConstructor(MultipartParser::class)
    }

    verify { exchange.send(InternalServerError, "<fault/>", soap) }
  }

  @Test fun `msh reaches decryptPayload and fails`() {
    val payload = "<hello>world</hello>"
    val body = getBody(payload)

    every { exchange.requestStream } returns body.inputStream()
    every { messageHandlers.rootTags } returns emptyMap()
    every { messageGenerator.soapFault(any(), any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { exchange.send(InternalServerError, "<fault/>", soap) }
  }

  @Test fun `msh success`() {
    val senderKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val receiverKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    val receiverCert = mockk<X509Certificate>()
    every { receiverCert.publicKey } returns receiverKey.public

    val senderId = PartyId("sender1")
    val receiverId = PartyId("receiver1")

    val generatorKeyManager = mockk<KeyManager> {
      every { partyId } returns senderId
      every { ownPrivateKey } returns senderKey.private
      every { ownCertSki } returns "sender-ski"
      every { receiverCert(receiverId) } returns receiverCert
      every { certSki(receiverCert) } returns "receiver-ski"
    }

    val generator = EDeliveryMessageGenerator(generatorKeyManager)
    val (xml, payload) = generator.requestMessage(UserMessageParams(RequestKey(
      receiverId, UUID.randomUUID(),
      senderId
    )), "<data/>")

    every { keyManager.ownPrivateKey } returns receiverKey.private
    every { keyManager.ownCertSki } returns "receiver-ski"
    every { messageGenerator.responseMessage(any()) } returns "<response/>"

    val senderParty = mockk<Party>()
    every { senderParty.id } returns senderId
    every { senderParty.eDeliveryUrl } returns URI("http://sender")
    every { partyRegistry[senderId] } returns senderParty

    val handlerStarted = CountDownLatch(1)
    val handlerProceed = CountDownLatch(1)
    val mockHandler2 = mockk<(MessageContext) -> String?>()
    every { mockHandler2.invoke(any()) } answers {
      handlerStarted.countDown()
      handlerProceed.await(5, TimeUnit.SECONDS)
      null
    }
    val mockHandlersMap = mockk<Map<String, (MessageContext) -> String?>>()
    every { mockHandlersMap[any()] } returns mockHandler2
    every { messageHandlers.rootTags } returns mockHandlersMap

    mockkConstructor(MultipartParser::class)
    every { anyConstructed<MultipartParser>().parse(any()) } returns mapOf("xml" to xml, "payload" to payload)
    every { exchange.requestStream } returns "multipart".toByteArray().inputStream()

    try { routes.msh(exchange) } finally { unmockkConstructor(MultipartParser::class) }

    // Response is sent synchronously before handler runs
    verify { exchange.send(OK, "<response/>", soap) }

    // Handler is run asynchronously - it has started but not yet completed
    expect(handlerStarted.count).toEqual(0L)
    expect(handlerProceed.count).toEqual(1L)

    // Let the handler complete
    handlerProceed.countDown()
    verify {
      mockHandler2.invoke(match {
        it.key.receiverId == senderId && it.xml.contains("<data/>")
      })
    }
  }
}
