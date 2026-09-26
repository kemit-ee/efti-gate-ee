package edelivery

import ch.tutteli.atrium.api.fluent.en_GB.toContain
import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.fluent.en_GB.toThrow
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.*
import klite.HttpExchange
import klite.StatusCode.Companion.InternalServerError
import klite.StatusCode.Companion.OK
import klite.base64Decode
import org.junit.jupiter.api.Test
import java.net.URI
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
  val signatureValidator = SignatureValidator()

  private val keyIdentifierRegex = Regex("<wsse:KeyIdentifier\\b[^>]*>.*?</wsse:KeyIdentifier>", setOf(RegexOption.DOT_MATCHES_ALL))

  init {
    every { keyManager.receiverCert(any()) } returns keyManager.ownCert
  }

  val routes = EDeliveryRoutes(keyManager, messageHandlers, messageGenerator, eDeliveryClient, partyRegistry, signatureValidator)

  private val boundary = "----boundary"
  private val cipherValueRegex = Regex("(<xenc:CipherValue>)(.*?)(</xenc:CipherValue>)", setOf(RegexOption.DOT_MATCHES_ALL))

  fun getBody(payload: String, keyInAnotherPart: Boolean = false, tamper: Boolean = false, headerTransform: (String) -> String = { it }): ByteArray {
    val (xml, encryptedPayload) = realMessageGenerator.requestMessage(
      UserMessageParams(RequestKey(party.id, senderId = party.id)), payload)

    val encryptedKey = cipherValueRegex.find(xml)?.groupValues?.get(2)?.base64Decode()
    val envelope = when {
      keyInAnotherPart -> cipherValueRegex.replace(xml) { it.groupValues[1] + it.groupValues[3] }
      tamper -> xml.replace("eftiGateAction", "tamperedAction")
      else -> xml
    }.let(headerTransform)

    val parts = mutableListOf(
      "--$boundary\r\nContent-Type: $soap\r\n\r\n".toByteArray(),
      envelope.toByteArray(),
      "\r\n".toByteArray()
    )
    if (keyInAnotherPart) {
      parts += listOf(
        "--$boundary\r\nContent-Type: application/ciphervalue\r\nContent-ID: <key>\r\n\r\n".toByteArray(),
        encryptedKey!!,
        "\r\n".toByteArray()
      )
    }
    parts += listOf(
      "--$boundary\r\nContent-Type: application/octet-stream\r\nContent-ID: <message>\r\n\r\n".toByteArray(),
      encryptedPayload,
      "\r\n--$boundary--".toByteArray()
    )
    return parts.reduce(ByteArray::plus)
  }

  @Test fun encryptedAesKeyInAnotherPart() {
    val payload = "<hello>world</hello>"
    val body = getBody(payload, keyInAnotherPart = true)

    every { exchange.requestStream } returns body.inputStream()

    every { mockHandler.invoke(any()) } returns "mockedResponse"
    every { messageHandlers.rootTags } returns mapOf("hello" to mockHandler)

    routes.msh(exchange)

    verify(timeout = 5000) {
      mockHandler.invoke(match {
        it.key.receiverId == party.id && it.xml.contains(payload)
      })
    }
  }

  @Test fun `msh fails when neither key identifier nor serial number present`() {
    val body = getBody("<hello>world</hello>") { keyIdentifierRegex.replace(it, "") }

    every { exchange.requestStream } returns body.inputStream()
    val exception = slot<Exception>()
    every { messageGenerator.soapFault(any(), capture(exception)) } returns "<fault/>"

    routes.msh(exchange)

    expect(exception.captured.message.orEmpty()).toContain("No valid KeyIdentifier or X509SerialNumber found in the message header.")
    verify { exchange.send(InternalServerError, "<fault/>", soap) }
  }

  @Test fun `msh fails with invalid key identifier`() {
    val body = getBody("<hello>world</hello>") {
      keyIdentifierRegex.replace(it, "<wsse:KeyIdentifier>invalid-ski</wsse:KeyIdentifier>")
    }

    every { exchange.requestStream } returns body.inputStream()
    val exception = slot<Exception>()
    every { messageGenerator.soapFault(any(), capture(exception)) } returns "<fault/>"

    routes.msh(exchange)

    expect(exception.captured.message.orEmpty()).toContain("Invalid KeyIdentifier \"invalid-ski\", expected \"${keyManager.ownCertSki}\"")
    verify { exchange.send(InternalServerError, "<fault/>", soap) }
  }

  @Test fun `msh fails with invalid serial number`() {
    val body = getBody("<hello>world</hello>") {
      keyIdentifierRegex.replace(it, serialNumberXml("123456789"))
    }

    every { exchange.requestStream } returns body.inputStream()
    val exception = slot<Exception>()
    every { messageGenerator.soapFault(any(), capture(exception)) } returns "<fault/>"

    routes.msh(exchange)

    expect(exception.captured.message.orEmpty()).toContain("Invalid X509SerialNumber \"123456789\", expected \"${keyManager.ownCertSerialNumber}\"")
    verify { exchange.send(InternalServerError, "<fault/>", soap) }
  }

  @Test fun `msh accepts valid serial number when no key identifier`() {
    val payload = "<hello>world</hello>"
    val body = getBody(payload) { keyIdentifierRegex.replace(it, serialNumberXml(keyManager.ownCertSerialNumber)) }

    every { exchange.requestStream } returns body.inputStream()
    every { mockHandler.invoke(any()) } returns "mockedResponse"
    every { messageHandlers.rootTags } returns mapOf("hello" to mockHandler)

    routes.msh(exchange)

    verify(timeout = 5000) { mockHandler.invoke(any()) }
  }

  private fun serialNumberXml(serialNumber: String) =
    "<wsse:X509Data><wsse:X509IssuerSerial><wsse:X509SerialNumber>$serialNumber</wsse:X509SerialNumber></wsse:X509IssuerSerial></wsse:X509Data>"


  @Test fun `gunzip rejects payload exceeding the limit`() {
    val bomb = realMessageGenerator.gzip(ByteArray(11 * 1024 * 1024))

    expect { gunzip(bomb) }.toThrow<IllegalArgumentException>()
  }

  @Test fun `gunzip accepts payload within the limit`() {
    val data = ByteArray(1024) { it.toByte() }

    expect(gunzip(realMessageGenerator.gzip(data)).toList()).toEqual(data.toList())
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
    every { exchange.requestStream } returns "invalid".toByteArray().inputStream()
    every { messageGenerator.soapFault(any(), any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { exchange.send(InternalServerError, "<fault/>", soap) }
  }

  @Test fun `msh reaches handler lookup and fails`() {
    val payload = "<hello>world</hello>"
    val body = getBody(payload)

    every { exchange.requestStream } returns body.inputStream()
    every { messageHandlers.rootTags } returns emptyMap()
    every { messageGenerator.soapFault(any(), any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { exchange.send(InternalServerError, "<fault/>", soap) }
  }

  @Test fun `msh success`() {
    val payload = "<data/>"
    val body = getBody(payload)

    every { exchange.requestStream } returns body.inputStream()
    every { messageGenerator.responseMessage(any()) } returns "<response/>"

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

    routes.msh(exchange)

    // Response is sent synchronously before handler runs
    verify { exchange.send(OK, "<response/>", soap) }

    // Handler is run asynchronously - it has started but not yet completed
    expect(handlerStarted.await(5, TimeUnit.SECONDS)).toEqual(true)
    expect(handlerProceed.count).toEqual(1L)

    // Let the handler complete
    handlerProceed.countDown()
    verify(timeout = 5000) {
      mockHandler2.invoke(match {
        it.key.receiverId == party.id && it.xml.contains(payload)
      })
    }
  }
}
