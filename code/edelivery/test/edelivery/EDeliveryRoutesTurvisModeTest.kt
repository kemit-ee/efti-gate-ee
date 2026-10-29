package edelivery

import ch.tutteli.atrium.api.fluent.en_GB.*
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.*
import klite.HttpExchange
import klite.base64Encode
import klite.StatusCode.Companion.InternalServerError
import klite.StatusCode.Companion.OK
import klite.StatusCode.Companion.ServiceUnavailable
import org.junit.jupiter.api.Test
import java.net.URI

/** The inbound route with `TURVIS_INTEGRATION_MODE=turvis` (#305b-1): Turvis is the authority, nothing is dispatched without a verify. */
class EDeliveryRoutesTurvisModeTest {
  private val audit = mockk<AsyncFailureAudit>(relaxed = true)
  private val dedup = mockk<InboundDedup> {
    every { find(any(), any()) } returns null
    every { record(any(), any(), any(), any()) } returns true
  }
  private val sender = EDeliveryParty(PartyId("de"), URI("http://de"), "")
  private val partyRegistry = mockk<PartyRegistry> {
    every { get(PartyId("DE")) } returns sender
    every { get(PartyId("MALLORY")) } throws NoSuchElementException("unknown")
  }
  private val keyManager = mockk<KeyManager> { every { partyId } returns PartyId("EU-EE") }
  private val handler = mockk<(MessageContext) -> String?>()
  private val messageHandlers = mockk<MessageHandlers> { every { rootTags } returns mapOf("hello" to handler) }
  private val generator = mockk<EDeliveryMessageGenerator>(relaxed = true)
  private val eDeliveryClient = mockk<EDeliveryClient>(relaxed = true)
  private val exchange = mockk<HttpExchange>(relaxed = true) {
    every { requestStream } returns "raw body".toByteArray().inputStream()
  }
  private val turvis = mockk<TurvisInbound> { every { authoritative } returns true }

  private val receiptBytes = "<receipt>\r\n signed </receipt>".toByteArray() + byteArrayOf(0x0b)
  private val outbound = mockk<TurvisOutbound> {
    every { receipt(any(), any(), any()) } returns SignedReceipt(receiptBytes, "sig-1")
  }

  private val routes = EDeliveryRoutes(keyManager, messageHandlers, generator, eDeliveryClient, partyRegistry, SignatureValidator(), turvis, TurvisShadow(TurvisConfig(keyHandle = "EU-EE"), turvis), outbound, audit, dedup).also { it.dispatch = { _, _, block -> block() } }

  private val conversation = "11111111-1111-1111-1111-111111111111"

  private fun verified(
    verifiedIdentity: String = "DE",
    from: String = "DE",
    conversationId: String? = conversation,
    payload: String = "<hello>world</hello>",
    digests: List<TurvisSignatureReference> = emptyList(),
    to: String = "EU-EE",
  ) = VerifiedInbound(
    TurvisEbmsRouting("m-1@x", conversationId, null, from, to, "svc", "act"),
    TurvisSigner("CN=de", "ski", "01", verifiedIdentity),
    digests, payload, "dec-1", "ver-1",
  )

  private fun rejection(code: String) = TurvisRejection("verifications", TurvisReason.of(code)?.httpStatus ?: 422, code, "aud-1", "turvis detail")

  @Test fun `verified message is dispatched by the verified identity and answered 200 with the signed receipt`() {
    every { turvis.process(any()) } returns verified()
    every { handler.invoke(any()) } returns null

    routes.msh(exchange)

    verify { exchange.send(OK, receiptBytes, soap) }
    verify(timeout = 5000) {
      handler.invoke(match {
        it.key.receiverId == PartyId("DE") && it.key.senderId == PartyId("EU-EE") &&
          it.key.requestId.toString() == conversation && it.xml == "<hello>world</hello>"
      })
    }
    verify(exactly = 0) { generator.responseMessage(any()) }
  }

  @Test fun `the receipt is built from the verified message id and the references Turvis reported`() {
    val digests = listOf(TurvisSignatureReference("#MSG-ID", "//eb:Messaging", "ZGln", "SHA-256", listOf("http://www.w3.org/2001/10/xml-exc-c14n#")))
    every { turvis.process(any()) } returns verified(digests = digests)
    every { handler.invoke(any()) } returns null
    val refTo = slot<String>()
    val refs = slot<List<TurvisSignatureReference>>()
    every { outbound.receipt(capture(refTo), capture(refs), any()) } returns SignedReceipt(receiptBytes, "sig-1")

    routes.msh(exchange)

    expect(refTo.captured).toEqual("m-1@x")
    expect(refs.captured).toEqual(digests)
    verify(exactly = 0) { generator.responseMessage(any()) }
  }

  @Test fun `the signed receipt reaches the peer byte for byte`() {
    every { turvis.process(any()) } returns verified()
    every { handler.invoke(any()) } returns null
    val sent = slot<ByteArray>()
    every { exchange.send(OK, capture(sent), soap) } just Runs

    routes.msh(exchange)

    expect(sent.captured.toList()).toEqual(receiptBytes.toList())
  }

  @Test fun `when the receipt cannot be signed the peer gets 503 and nothing is dispatched`() {
    listOf<Exception>(
      KeyUnavailableException(rejection("KEY_UNAVAILABLE")),
      OutboundSignBuildException(rejection("SIG_BUILD_FAIL")),
      TurvisUnavailableException("down"),
      TurvisProtocolException("signed envelope is not the unsigned one plus a trailing ds:Signature"),
      MalformedEnvelopeException(rejection("XML_PARSE_FAIL")),
      AlgoUnsupportedException(rejection("ALGO_UNSUPPORTED")),
      TurvisRejection("signatures", 422, "BRAND_NEW_CODE", "aud-1", null).toException(),
      IllegalStateException("generator bug"),
    ).forEach { ex ->
      clearMocks(exchange, generator, handler, answers = false, recordedCalls = true)
      every { exchange.requestStream } returns "raw body".toByteArray().inputStream()
      every { turvis.process(any()) } returns verified()
      every { outbound.receipt(any(), any(), any()) } throws ex

      routes.msh(exchange)

      verify { exchange.send(ServiceUnavailable, "", soap) }
      verify(exactly = 0) { generator.soapFault(any()) }
      verify(exactly = 0) { handler.invoke(any()) }
    }
  }

  @Test fun `the receipt is signed and sent before the message is dispatched`() {
    every { turvis.process(any()) } returns verified()
    every { handler.invoke(any()) } returns null

    routes.msh(exchange)

    verify(timeout = 5000) { handler.invoke(any()) }
    verifyOrder { outbound.receipt(any(), any(), any()); exchange.send(OK, receiptBytes, soap); handler.invoke(any()) }
  }

  @Test fun `the party is looked up by the signature identity, not by the envelope sender`() {
    every { turvis.process(any()) } returns verified(verifiedIdentity = "DE", from = "MALLORY")
    every { handler.invoke(any()) } returns null

    routes.msh(exchange)

    verify { partyRegistry[PartyId("DE")] }
    verify(exactly = 0) { partyRegistry[PartyId("MALLORY")] }
    verify(timeout = 5000) { handler.invoke(match { it.key.receiverId == PartyId("DE") }) }
  }

  @Test fun `handler result is sent back to the verified sender`() {
    every { turvis.process(any()) } returns verified()
    every { handler.invoke(any()) } returns "<answer/>"

    routes.msh(exchange)

    verify(timeout = 5000) { eDeliveryClient.send(sender.eDeliveryUrl, any(), "<answer/>") }
  }

  @Test fun `the body reaches turvis exactly as received`() {
    val sent = slot<ByteArray>()
    every { turvis.process(capture(sent)) } returns verified()
    every { handler.invoke(any()) } returns null

    routes.msh(exchange)

    expect(String(sent.captured)).toEqual("raw body")
  }

  @Test fun `a reject with a peer-facing reason answers a fault and dispatches nothing`() {
    every { turvis.process(any()) } throws SignerUntrustedException(rejection("SIGNER_UNTRUSTED"))
    every { generator.soapFault(any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { generator.soapFault(FaultReason.SIGNER_UNTRUSTED) }
    verify { exchange.send(InternalServerError, "<fault/>", soap) }
    verify(exactly = 0) { handler.invoke(any()) }
    verify(exactly = 0) { exchange.send(OK, any<ByteArray>(), any()) }
  }

  @Test fun `an unknown reject code fails closed as a signature fault`() {
    every { turvis.process(any()) } throws UnrecognisedRejectException(rejection("NEW_CODE_WE_DO_NOT_KNOW"))
    every { generator.soapFault(any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { generator.soapFault(FaultReason.SIG_CRYPTO_FAIL) }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `a sender that differs from the signer is a fault, nothing is dispatched`() {
    every { turvis.process(any()) } throws SignerIdentityMismatchException(rejection("SIGNER_IDENTITY_MISMATCH"))
    every { generator.soapFault(any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { generator.soapFault(FaultReason.SIGNER_IDENTITY_MISMATCH) }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `a reject that is not the peer's doing answers 503 without a fault`() {
    every { turvis.process(any()) } throws KeyUnavailableException(rejection("KEY_UNAVAILABLE"))

    routes.msh(exchange)

    verify { exchange.send(ServiceUnavailable, "", soap) }
    verify(exactly = 0) { generator.soapFault(any()) }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `turvis internal error answers 503 without a fault`() {
    every { turvis.process(any()) } throws TurvisInternalException(rejection("TURVIS_INTERNAL"))

    routes.msh(exchange)

    verify { exchange.send(ServiceUnavailable, "", soap) }
    verify(exactly = 0) { generator.soapFault(any()) }
  }

  @Test fun `unreachable, protocol-broken or unauthorised turvis answers 503 without a fault`() {
    listOf<Exception>(TurvisUnavailableException("down"), TurvisProtocolException("garbage"), TurvisAuthException(401, "verifications")).forEach { ex ->
      clearMocks(exchange, generator, answers = false, recordedCalls = true)
      every { exchange.requestStream } returns "raw body".toByteArray().inputStream()
      every { turvis.process(any()) } throws ex

      routes.msh(exchange)

      verify { exchange.send(ServiceUnavailable, "", soap) }
      verify(exactly = 0) { generator.soapFault(any()) }
    }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `a malformed multipart body is a parse fault and never dispatches`() {
    every { turvis.process(any()) } throws IllegalArgumentException("Multipart body has no closing boundary")
    every { generator.soapFault(any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { generator.soapFault(FaultReason.XML_PARSE_FAIL) }
    verify { exchange.send(InternalServerError, "<fault/>", soap) }
  }

  @Test fun `a verified message without a conversation id is refused before dispatch`() {
    every { turvis.process(any()) } returns verified(conversationId = null)
    every { generator.soapFault(any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { exchange.send(InternalServerError, "<fault/>", soap) }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `a conversation id that is not a UUID is refused before dispatch`() {
    every { turvis.process(any()) } returns verified(conversationId = "not-a-uuid")
    every { generator.soapFault(any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { exchange.send(InternalServerError, "<fault/>", soap) }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `a verified signer that is not a registered party is refused before dispatch`() {
    every { turvis.process(any()) } returns verified(verifiedIdentity = "MALLORY", from = "MALLORY")
    every { generator.soapFault(any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { exchange.send(InternalServerError, "<fault/>", soap) }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `an unsupported root tag is refused before the 200`() {
    every { turvis.process(any()) } returns verified(payload = "<unknown/>")
    every { generator.soapFault(any()) } returns "<fault/>"

    routes.msh(exchange)

    verify { generator.soapFault(FaultReason.UNSUPPORTED_MESSAGE) }
    verify(exactly = 0) { exchange.send(OK, any<ByteArray>(), any()) }
  }

  @Test fun `with the real TurvisInbound a verify reject is answered with a fault and never dispatched`() {
    val gzipped = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write("<hello/>".toByteArray()) } }.toByteArray()
    val client = mockk<TurvisClient> {
      every { decrypt(any(), any(), any(), any()) } returns TurvisResult.Accept(
        TurvisDecryptResponse("00000000-0000-4000-8000-000000000001", "PHMvPg==", gzipped.base64Encode(), null))
      every { verify(any(), any(), any(), any(), any(), any(), any(), any()) } returns TurvisResult.Reject(
        TurvisRejection("verifications", 422, "SIG_CRYPTO_FAIL", "00000000-0000-4000-8000-000000000002", "detail"))
    }
    val config = TurvisConfig(mode = TurvisIntegrationMode.TURVIS, keyHandle = "EU-EE")
    val real = EDeliveryRoutes(keyManager, messageHandlers, generator, eDeliveryClient, partyRegistry, SignatureValidator(), TurvisInbound(config, client), TurvisShadow(config, turvis), outbound, audit, dedup)
    every { exchange.requestStream } returns "--b\r\n\r\n<s/>\r\n--b\r\nContent-ID: <message>\r\n\r\nAB\r\n--b--".toByteArray().inputStream()
    every { generator.soapFault(any()) } returns "<fault/>"

    real.msh(exchange)

    verify { generator.soapFault(FaultReason.SIG_CRYPTO_FAIL) }
    verify { exchange.send(InternalServerError, "<fault/>", soap) }
    verify(exactly = 0) { handler.invoke(any()) }
    verify(exactly = 0) { partyRegistry[any()] }
  }

  @Test fun `the peer never sees the exception text of a failure`() {
    every { turvis.process(any()) } throws IllegalStateException("<driver>Jaan Tamm</driver> C:\\internal")
    val real = EDeliveryRoutes(keyManager, messageHandlers, EDeliveryMessageGenerator(mockk(relaxed = true)), eDeliveryClient, partyRegistry, SignatureValidator(), turvis, TurvisShadow(TurvisConfig(keyHandle = "EU-EE"), turvis), outbound, audit, dedup)
    val sent = slot<String>()
    every { exchange.send(InternalServerError, capture(sent), soap) } just Runs

    real.msh(exchange)

    expect(sent.captured).notToContain("Jaan Tamm")
    expect(sent.captured).notToContain("internal")
    expect(sent.captured).toContain(FaultReason.OTHER.ebmsCode)
  }

  @Test fun `a peer fault from a Turvis reject carries only the bucket, with and without a Turvis audit_ref or detail`() {
    val real = EDeliveryRoutes(keyManager, messageHandlers, EDeliveryMessageGenerator(mockk(relaxed = true)), eDeliveryClient, partyRegistry, SignatureValidator(), turvis, TurvisShadow(TurvisConfig(keyHandle = "EU-EE"), turvis), outbound, audit, dedup)
    val withRef = TurvisReason.of("SIGNER_UNTRUSTED")!!.let { TurvisRejection("verifications", it.httpStatus, "SIGNER_UNTRUSTED", "00000000-0000-4000-8000-00000000abcd", "Jaan Tamm C:\\internal") }
    val withoutRef = TurvisRejection("verifications", 422, "SIGNER_UNTRUSTED", null, null)
    for (rejection in listOf(withRef, withoutRef)) {
      clearMocks(exchange, answers = false, recordedCalls = true)
      every { exchange.requestStream } returns "raw body".toByteArray().inputStream()
      every { turvis.process(any()) } throws SignerUntrustedException(rejection)
      val sent = slot<String>()
      every { exchange.send(InternalServerError, capture(sent), soap) } just Runs

      real.msh(exchange)

      expect(sent.captured).toContain(FaultReason.SIGNER_UNTRUSTED.ebmsCode)
      expect(sent.captured).notToContain("abcd")
      expect(sent.captured).notToContain("audit_ref")
      expect(sent.captured).notToContain("Jaan Tamm")
      expect(sent.captured).notToContain("internal")
      expect(sent.captured).notToContain("Exception")
    }
  }

  @Test fun `the error log line of a Turvis reject carries the audit_ref and the bucket, with and without a Turvis audit_ref, and no detail`() {
    val withRef = TurvisRejection("verifications", 422, "SIGNER_UNTRUSTED", "00000000-0000-4000-8000-00000000abcd", "Jaan Tamm C:\\internal")
    val withoutRef = TurvisRejection("verifications", 422, "SIGNER_UNTRUSTED", null, null)
    every { generator.soapFault(any()) } returns "<fault/>"
    for ((rejection, turvisRef) in listOf(withRef to "00000000-0000-4000-8000-00000000abcd", withoutRef to "null")) {
      clearMocks(exchange, answers = false, recordedCalls = true)
      every { exchange.requestStream } returns "raw body".toByteArray().inputStream()
      every { turvis.process(any()) } throws SignerUntrustedException(rejection)

      val lines = LogCapture.lines { routes.msh(exchange) }

      val line = lines.single { "Error when processing message via turvis" in it }
      expect(line).toContain("audit_ref=")
      expect(line).toContain("reason=SIGNER_UNTRUSTED")
      expect(line).toContain("turvis_audit_ref=$turvisRef")
      expect(line).toContain("body_sha256=")
      expect(line).notToContain("Jaan Tamm")
      expect(line).notToContain("internal")
      expect(line).notToContain("raw body")
      expect(line).notToContain("detail")
    }
  }

  @Test fun `an unknown receiver from the verified routing is logged only in the audit character set`() {
    every { turvis.process(any()) } returns verified(to = "EVIL\r\n{\"forged\":1} receiver")
    every { handler.invoke(any()) } returns null

    val lines = LogCapture.lines { routes.msh(exchange) }

    expect(lines.single { "Unknown receiver" in it }).toContain("Unknown receiver: ?")
    expect(lines.joinToString("\n")).notToContain("forged")
    expect(lines.joinToString("\n")).notToContain("EVIL")
  }

  private val storedReceipt = "<stored-receipt>\r\n first </stored-receipt>".toByteArray() + byteArrayOf(0x0b)
  private val payloadSha = java.security.MessageDigest.getInstance("SHA-256").digest("<hello>world</hello>".toByteArray()).joinToString("") { "%02x".format(it) }

  @Test fun `a repeat of an accepted message gets the stored receipt byte for byte and is neither signed nor dispatched`() {
    every { turvis.process(any()) } returns verified()
    every { dedup.find(PartyId("DE"), "m-1@x") } returns AcceptedInbound(storedReceipt, payloadSha)
    val before = EDeliveryMetrics.inboundDuplicateCounts()["same_payload"] ?: 0

    routes.msh(exchange)

    verify { exchange.send(OK, storedReceipt, soap) }
    verify(exactly = 0) { outbound.receipt(any(), any(), any()) }
    verify(exactly = 0) { dedup.record(any(), any(), any(), any()) }
    verify(exactly = 0) { handler.invoke(any()) }
    expect(EDeliveryMetrics.inboundDuplicateCounts()["same_payload"] ?: 0).toEqual(before + 1)
  }

  @Test fun `a repeat with the same id but another payload gets a SOAP fault, not the stored receipt, is not dispatched and is counted as a content mismatch`() {
    every { turvis.process(any()) } returns verified(payload = "<hello>different</hello>")
    every { dedup.find(PartyId("DE"), "m-1@x") } returns AcceptedInbound(storedReceipt, payloadSha)
    every { generator.soapFault(FaultReason.OTHER) } returns "<fault/>"
    val before = EDeliveryMetrics.inboundDuplicateCounts()["content_mismatch"] ?: 0

    routes.msh(exchange)

    verify { exchange.send(InternalServerError, "<fault/>", soap) }
    verify(exactly = 0) { exchange.send(OK, any<ByteArray>(), any()) }
    verify(exactly = 0) { outbound.receipt(any(), any(), any()) }
    verify(exactly = 0) { handler.invoke(any()) }
    expect(EDeliveryMetrics.inboundDuplicateCounts()["content_mismatch"] ?: 0).toEqual(before + 1)
  }

  @Test fun `a new message is recorded with the receipt and the payload hash before the answer and the dispatch`() {
    every { turvis.process(any()) } returns verified()
    every { handler.invoke(any()) } returns null

    routes.msh(exchange)

    verify(timeout = 5000) { handler.invoke(any()) }
    verifyOrder {
      dedup.find(PartyId("DE"), "m-1@x")
      outbound.receipt(any(), any(), any())
      dedup.record(PartyId("DE"), "m-1@x", payloadSha, receiptBytes)
      exchange.send(OK, receiptBytes, soap)
      handler.invoke(any())
    }
  }

  @Test fun `the key is the verified identity, never the unsigned envelope sender`() {
    every { turvis.process(any()) } returns verified(verifiedIdentity = "DE", from = "DE")
    every { handler.invoke(any()) } returns null
    routes.msh(exchange)
    verify(timeout = 5000) { handler.invoke(any()) }
    verify { dedup.find(PartyId("DE"), any()) }

    clearMocks(dedup, answers = false, recordedCalls = true)
    every { exchange.requestStream } returns "raw body".toByteArray().inputStream()
    every { turvis.process(any()) } returns verified(verifiedIdentity = "DE", from = "MALLORY")
    routes.msh(exchange)
    verify(exactly = 0) { dedup.find(PartyId("MALLORY"), any()) }
  }

  @Test fun `losing the race to another node answers with the winner's receipt and does not dispatch`() {
    every { turvis.process(any()) } returns verified()
    every { dedup.find(PartyId("DE"), "m-1@x") } returnsMany listOf(null, AcceptedInbound(storedReceipt, payloadSha))
    every { dedup.record(any(), any(), any(), any()) } returns false

    routes.msh(exchange)

    verify { exchange.send(OK, storedReceipt, soap) }
    verify(exactly = 0) { exchange.send(OK, receiptBytes, soap) }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `an unreadable duplicate store is HTTP 503 without a fault, nothing is dispatched and the outage is counted`() {
    val before = EDeliveryMetrics.inboundDedupUnavailableCount()
    every { turvis.process(any()) } returns verified()
    every { dedup.find(any(), any()) } throws InboundDedupUnavailableException()

    routes.msh(exchange)

    verify { exchange.send(ServiceUnavailable, "", soap) }
    verify(exactly = 0) { generator.soapFault(any()) }
    verify(exactly = 0) { outbound.receipt(any(), any(), any()) }
    verify(exactly = 0) { handler.invoke(any()) }
    expect(EDeliveryMetrics.inboundDedupUnavailableCount()).toEqual(before + 1)
  }

  @Test fun `a store that cannot record is HTTP 503 without a fault, the receipt is not sent and nothing is dispatched`() {
    every { turvis.process(any()) } returns verified()
    every { dedup.record(any(), any(), any(), any()) } throws InboundDedupUnavailableException()

    routes.msh(exchange)

    verify { exchange.send(ServiceUnavailable, "", soap) }
    verify(exactly = 0) { exchange.send(OK, any<ByteArray>(), any()) }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `a receipt that could not be signed records nothing, so the peer's retry is a first delivery`() {
    every { turvis.process(any()) } returns verified()
    every { outbound.receipt(any(), any(), any()) } throws TurvisUnavailableException("down")

    routes.msh(exchange)

    verify { exchange.send(ServiceUnavailable, "", soap) }
    verify(exactly = 0) { dedup.record(any(), any(), any(), any()) }
  }

  @Test fun `once recorded the message is dispatched even if the answer cannot be written`() {
    every { turvis.process(any()) } returns verified()
    every { handler.invoke(any()) } returns null
    every { exchange.send(OK, receiptBytes, soap) } throws java.io.IOException("peer went away")

    routes.msh(exchange)

    verify(timeout = 5000) { handler.invoke(any()) }
    verify(exactly = 0) { exchange.send(ServiceUnavailable, any<String>(), any()) }
  }

  @Test fun `a message id that is too long is a malformed message with a fault, not an outage and not recorded`() {
    every { turvis.process(any()) } returns verified()
    val real = InboundDedup(mockk(relaxed = true))
    val routesWithRealDedup = EDeliveryRoutes(keyManager, messageHandlers, generator, eDeliveryClient, partyRegistry, SignatureValidator(), turvis, TurvisShadow(TurvisConfig(keyHandle = "EU-EE"), turvis), outbound, audit, real)
    every { turvis.process(any()) } returns VerifiedInbound(
      TurvisEbmsRouting("m".repeat(InboundDedup.MAX_MESSAGE_ID_LENGTH + 1), conversation, null, "DE", "EU-EE", "svc", "act"),
      TurvisSigner("CN=de", "ski", "01", "DE"), emptyList(), "<hello>world</hello>", "dec-1", "ver-1",
    )

    routesWithRealDedup.msh(exchange)

    verify(exactly = 0) { exchange.send(ServiceUnavailable, any<String>(), any()) }
    verify(exactly = 0) { outbound.receipt(any(), any(), any()) }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `a record whose answer was lost but that committed is dispatched once, the stored receipt being ours`() {
    every { turvis.process(any()) } returns verified()
    every { handler.invoke(any()) } returns null
    every { dedup.record(any(), any(), any(), any()) } throws InboundDedupUnavailableException()
    every { dedup.find(PartyId("DE"), "m-1@x") } returnsMany listOf(null, AcceptedInbound(receiptBytes, payloadSha))

    routes.msh(exchange)

    verify(exactly = 1) { handler.invoke(any()) }
    verify { exchange.send(OK, receiptBytes, soap) }
    verify(exactly = 0) { exchange.send(ServiceUnavailable, any<String>(), any()) }
  }

  @Test fun `a record whose answer was lost and that finds another receipt is a repeat, not dispatched`() {
    every { turvis.process(any()) } returns verified()
    every { dedup.record(any(), any(), any(), any()) } throws InboundDedupUnavailableException()
    every { dedup.find(PartyId("DE"), "m-1@x") } returnsMany listOf(null, AcceptedInbound(storedReceipt, payloadSha))

    routes.msh(exchange)

    verify { exchange.send(OK, storedReceipt, soap) }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `a lost record answer followed by an unreadable store stays HTTP 503 and nothing is dispatched`() {
    every { turvis.process(any()) } returns verified()
    every { dedup.record(any(), any(), any(), any()) } throws InboundDedupUnavailableException()
    every { dedup.find(any(), any()) } returnsMany listOf(null) andThenThrows InboundDedupUnavailableException()

    routes.msh(exchange)

    verify { exchange.send(ServiceUnavailable, "", soap) }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `a repeat whose stored receipt cannot be written to the peer is still not dispatched and gets no second answer`() {
    every { turvis.process(any()) } returns verified()
    every { dedup.find(PartyId("DE"), "m-1@x") } returns AcceptedInbound(storedReceipt, payloadSha)
    every { exchange.send(OK, storedReceipt, soap) } throws java.io.IOException("peer went away")

    routes.msh(exchange)

    verify(exactly = 0) { exchange.send(ServiceUnavailable, any<String>(), any()) }
    verify(exactly = 0) { handler.invoke(any()) }
  }

  @Test fun `a dispatch that cannot even be started after the receipt is logged, the peer is not answered a second time`() {
    every { turvis.process(any()) } returns verified()
    routes.dispatch = { _, _, _ -> throw IllegalStateException("executor gone") }

    routes.msh(exchange)

    verify(exactly = 1) { exchange.send(OK, receiptBytes, soap) }
    verify(exactly = 0) { exchange.send(ServiceUnavailable, any<String>(), any()) }
    verify(exactly = 0) { exchange.send(InternalServerError, any<String>(), any()) }
  }

  @Test fun `a duplicate is never dispatched later either, the dispatcher is never asked`() {
    every { turvis.process(any()) } returns verified()
    every { dedup.find(PartyId("DE"), "m-1@x") } returns AcceptedInbound(storedReceipt, payloadSha)
    var asked = 0
    routes.dispatch = { _, _, _ -> asked++ }

    routes.msh(exchange)

    expect(asked).toEqual(0)
  }
}
