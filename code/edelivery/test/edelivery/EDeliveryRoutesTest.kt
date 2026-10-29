package edelivery

import ch.tutteli.atrium.api.fluent.en_GB.notToContain
import ch.tutteli.atrium.api.fluent.en_GB.toContain
import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.fluent.en_GB.toThrow
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.*
import klite.HttpExchange
import klite.StatusCode.Companion.BadRequest
import klite.StatusCode.Companion.InternalServerError
import klite.StatusCode.Companion.OK
import klite.base64Decode
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EDeliveryRoutesTest {
  val audit = mockk<AsyncFailureAudit>(relaxed = true)
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
  /** Mode legacy (the default): the Turvis inbound pass is not used and the client is never called. */
  val legacyInbound = TurvisInbound(TurvisConfig(keyHandle = "EU-EE"), mockk(relaxed = true))
  val legacyShadow = TurvisShadow(TurvisConfig(keyHandle = "EU-EE"), legacyInbound)

  private val keyIdentifierRegex = Regex("<wsse:KeyIdentifier\\b[^>]*>.*?</wsse:KeyIdentifier>", setOf(RegexOption.DOT_MATCHES_ALL))

  init {
    every { keyManager.receiverCert(any()) } returns keyManager.ownCert
  }

  val routes = EDeliveryRoutes(keyManager, messageHandlers, messageGenerator, eDeliveryClient, partyRegistry, signatureValidator, legacyInbound, legacyShadow, mockk(relaxed = true), audit, mockk(relaxed = true))

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
    val reason = slot<FaultReason>()
    every { messageGenerator.soapFault(capture(reason)) } returns "<fault/>"

    routes.msh(exchange)

    expect(reason.captured).toEqual(FaultReason.XML_PARSE_FAIL)
    verify { exchange.send(InternalServerError, "<fault/>", soap) }
  }

  @Test fun `msh fails with invalid key identifier`() {
    val body = getBody("<hello>world</hello>") {
      keyIdentifierRegex.replace(it, "<wsse:KeyIdentifier>invalid-ski</wsse:KeyIdentifier>")
    }

    every { exchange.requestStream } returns body.inputStream()
    val reason = slot<FaultReason>()
    every { messageGenerator.soapFault(capture(reason)) } returns "<fault/>"

    routes.msh(exchange)

    expect(reason.captured).toEqual(FaultReason.XML_PARSE_FAIL)
    verify { exchange.send(InternalServerError, "<fault/>", soap) }
  }

  @Test fun `msh fails with invalid serial number`() {
    val body = getBody("<hello>world</hello>") {
      keyIdentifierRegex.replace(it, serialNumberXml("123456789"))
    }

    every { exchange.requestStream } returns body.inputStream()
    val reason = slot<FaultReason>()
    every { messageGenerator.soapFault(capture(reason)) } returns "<fault/>"

    routes.msh(exchange)

    expect(reason.captured).toEqual(FaultReason.XML_PARSE_FAIL)
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

    expect { gunzip(bomb) }.toThrow<PayloadTooLargeException>()
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
    every { messageGenerator.soapFault(any()) } returns "<SOAP-ENV:Fault/>"

    routes.msh(exchange)

    verify {
      exchange.send(InternalServerError, match<String> { it.contains("Fault", ignoreCase = true) }, soap)
    }
  }

  @Test fun `msh fails with invalid body`() {
    every { exchange.requestStream } returns "invalid".toByteArray().inputStream()
    every { messageGenerator.soapFault(any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { exchange.send(BadRequest, "<fault/>", soap) }
  }

  @Test fun `the error log line of a failed message carries audit_ref, reason and a body fingerprint, never the body or the exception text`() {
    every { exchange.requestStream } returns "invalid ${secret}".toByteArray().inputStream()
    every { messageGenerator.soapFault(any()) } returns "<fault/>"

    val lines = LogCapture.lines { routes.msh(exchange) }

    val line = lines.single { "Error when processing message" in it }
    expect(line).toContain("audit_ref=")
    expect(line).toContain("reason=")
    expect(line).toContain("body_sha256=")
    expect(line).toContain("body_size_bytes=")
    expect(line).notToContain(secret)
    expect(line).notToContain("invalid")
  }

  @Test fun `an unknown receiver in the envelope is logged only in the audit character set`() {
    val body = getBody("<hello>world</hello>", headerTransform = {
      it.replace(Regex("(<eb:To>\\s*<eb:PartyId[^>]*>)[^<]*"), "$1EVIL\r\nforged-line")
    })
    every { exchange.requestStream } returns body.inputStream()
    every { messageHandlers.rootTags } throws IllegalStateException(secret)

    val lines = LogCapture.lines { routes.msh(exchange) }

    expect(lines.single { "Unknown receiver" in it }).toContain("Unknown receiver: ?")
    expect(lines.joinToString("\n")).notToContain("forged")
    expect(lines.joinToString("\n")).notToContain("EVIL")
  }

  @Test fun `a multipart with a single part is a 400 with the XML_PARSE_FAIL fault`() {
    val onlyPart = "--$boundary\r\nContent-Type: $soap\r\n\r\n<Envelope/>\r\n--$boundary--"
    every { exchange.requestStream } returns onlyPart.toByteArray().inputStream()
    val reason = slot<FaultReason>()
    every { messageGenerator.soapFault(capture(reason)) } returns "<fault/>"

    routes.msh(exchange)

    expect(reason.captured).toEqual(FaultReason.XML_PARSE_FAIL)
    verify { exchange.send(BadRequest, "<fault/>", soap) }
  }

  @Test fun `a part of the wrong type is a 400 and never a ClassCastException`() {
    val twoTextParts = "--$boundary\r\nContent-Type: $soap\r\n\r\n<Envelope/>\r\n" +
      "--$boundary\r\nContent-Type: $soap\r\n\r\n<Other/>\r\n--$boundary--"
    every { exchange.requestStream } returns twoTextParts.toByteArray().inputStream()
    every { messageGenerator.soapFault(any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { exchange.send(BadRequest, "<fault/>", soap) }
  }

  @Test fun `a handler that throws after the 200 is counted and does not escape`() {
    val before = EDeliveryMetrics.asyncHandlerFailureCounts()["unsupported_message"] ?: 0
    val body = getBody("<hello>world</hello>")
    every { exchange.requestStream } returns body.inputStream()
    every { mockHandler.invoke(any()) } throws UnsupportedOperationException("secret payload fragment")
    every { messageHandlers.rootTags } returns mapOf("hello" to mockHandler)

    routes.msh(exchange)

    verify(timeout = 5000) { mockHandler.invoke(any()) }
    val deadline = System.currentTimeMillis() + 5000
    while ((EDeliveryMetrics.asyncHandlerFailureCounts()["unsupported_message"] ?: 0) <= before && System.currentTimeMillis() < deadline) Thread.sleep(10)
    expect(EDeliveryMetrics.asyncHandlerFailureCounts()["unsupported_message"] ?: 0).toEqual(before + 1)
    verify(timeout = 5000) {
      audit.record(any(), "handler", FaultReason.UNSUPPORTED_MESSAGE, match { it.keys == setOf("response_key") && !it.values.any { v -> v.contains("secret") } })
    }
  }

  private val secret = "<driver>Jaan Tamm</driver> C:\\internal\\path CN=Internal"

  private fun sentFault(status: klite.StatusCode = InternalServerError): String {
    val sent = slot<String>()
    verify { exchange.send(status, capture(sent), soap) }
    return sent.captured
  }

  private fun assertBucketOnly(fault: String, reason: FaultReason) {
    expect(fault).toContain(reason.ebmsCode)
    expect(fault).toContain(reason.name)
    expect(fault).notToContain("Jaan Tamm")
    expect(fault).notToContain("internal")
    expect(fault).notToContain("Exception")
    expect(fault).notToContain("\tat ")
    expect(fault).notToContain("edelivery.EDeliveryRoutes")
  }

  @Test fun `real SOAP fault never carries the message of the exception that was thrown`() {
    val body = getBody("<hello>world</hello>")
    every { exchange.requestStream } returns body.inputStream()
    every { messageHandlers.rootTags } throws IllegalStateException(secret)

    EDeliveryRoutes(keyManager, messageHandlers, realMessageGenerator, eDeliveryClient, partyRegistry, signatureValidator, legacyInbound, legacyShadow, mockk(relaxed = true), audit, mockk(relaxed = true)).msh(exchange)

    assertBucketOnly(sentFault(), FaultReason.OTHER)
  }

  @Test fun `real SOAP fault for a security failure is the signature bucket without the exception text`() {
    val body = getBody("<hello>world</hello>")
    every { exchange.requestStream } returns body.inputStream()
    every { messageHandlers.rootTags } throws SecurityException(secret)

    EDeliveryRoutes(keyManager, messageHandlers, realMessageGenerator, eDeliveryClient, partyRegistry, signatureValidator, legacyInbound, legacyShadow, mockk(relaxed = true), audit, mockk(relaxed = true)).msh(exchange)

    assertBucketOnly(sentFault(), FaultReason.SIG_CRYPTO_FAIL)
  }

  @Test fun `real SOAP fault for an unparsable body does not echo the body`() {
    every { exchange.requestStream } returns "invalid $secret".toByteArray().inputStream()

    EDeliveryRoutes(keyManager, messageHandlers, realMessageGenerator, eDeliveryClient, partyRegistry, signatureValidator, legacyInbound, legacyShadow, mockk(relaxed = true), audit, mockk(relaxed = true)).msh(exchange)

    val fault = sentFault(BadRequest)
    expect(fault).notToContain("Jaan Tamm")
    expect(fault).notToContain("internal")
    expect(fault).notToContain("Exception")
  }

  @Test fun `msh reaches handler lookup and fails`() {
    val payload = "<hello>world</hello>"
    val body = getBody(payload)

    every { exchange.requestStream } returns body.inputStream()
    every { messageHandlers.rootTags } returns emptyMap()
    every { messageGenerator.soapFault(any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { exchange.send(InternalServerError, "<fault/>", soap) }
  }

  @Test fun `shadow mode is told what legacy decided, after the peer was answered`() {
    val shadow = mockk<TurvisShadow>(relaxed = true)
    val decision = slot<LegacyDecision>()
    every { shadow.compareInbound(any(), any(), capture(decision)) } just Runs
    every { exchange.requestStream } returns getBody("<data/>").inputStream()
    every { messageGenerator.responseMessage(any()) } returns "<response/>"
    every { mockHandler.invoke(any()) } returns null
    every { messageHandlers.rootTags } returns mapOf("data" to mockHandler)

    EDeliveryRoutes(keyManager, messageHandlers, messageGenerator, eDeliveryClient, partyRegistry, signatureValidator, legacyInbound, shadow, mockk(relaxed = true), audit, mockk(relaxed = true)).msh(exchange)

    verifyOrder {
      exchange.send(OK, "<response/>", soap)
      shadow.compareInbound(any(), any(), any())
    }
    val observed = (decision.captured as LegacyDecision.Accepted).observation
    expect(observed.stage).toEqual(LegacyObservation.Stage.VERIFIED)
    expect(observed.senderId).toEqual(party.id)
    expect(observed.payloadXml).toEqual("<data/>")
    expect(observed.attachment!!.isNotEmpty()).toEqual(true)
  }

  @Test fun `shadow mode sees a legacy signature rejection as a reject with its bucket`() {
    val shadow = mockk<TurvisShadow>(relaxed = true)
    val decision = slot<LegacyDecision>()
    every { shadow.compareInbound(any(), any(), capture(decision)) } just Runs
    every { exchange.requestStream } returns getBody("<data/>", tamper = true).inputStream()

    EDeliveryRoutes(keyManager, messageHandlers, messageGenerator, eDeliveryClient, partyRegistry, signatureValidator, legacyInbound, shadow, mockk(relaxed = true), audit, mockk(relaxed = true)).msh(exchange)

    expect(decision.captured).toEqual(LegacyDecision.Rejected(FaultReason.SIG_CRYPTO_FAIL, party.id))
    verify(exactly = 0) { messageHandlers.rootTags }
  }

  @Test fun `an unknown sender party is not a comparable legacy decision`() {
    val shadow = mockk<TurvisShadow>(relaxed = true)
    val decision = slot<LegacyDecision>()
    every { shadow.compareInbound(any(), any(), capture(decision)) } just Runs
    every { exchange.requestStream } returns getBody("<data/>").inputStream()
    every { partyRegistry[party.id] } throws NoSuchElementException("unknown")

    EDeliveryRoutes(keyManager, messageHandlers, messageGenerator, eDeliveryClient, partyRegistry, signatureValidator, legacyInbound, shadow, mockk(relaxed = true), audit, mockk(relaxed = true)).msh(exchange)

    expect(decision.captured).toEqual(LegacyDecision.NotComparable)
  }

  @Test fun `a failure after verification (unsupported root tag) is still an accepted security decision`() {
    val shadow = mockk<TurvisShadow>(relaxed = true)
    val decision = slot<LegacyDecision>()
    every { shadow.compareInbound(any(), any(), capture(decision)) } just Runs
    every { exchange.requestStream } returns getBody("<unknown/>").inputStream()
    every { messageHandlers.rootTags } returns emptyMap()

    EDeliveryRoutes(keyManager, messageHandlers, messageGenerator, eDeliveryClient, partyRegistry, signatureValidator, legacyInbound, shadow, mockk(relaxed = true), audit, mockk(relaxed = true)).msh(exchange)

    expect(decision.captured is LegacyDecision.Accepted).toEqual(true)
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
