package edelivery

import ch.tutteli.atrium.api.fluent.en_GB.*
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.*
import klite.HttpExchange
import klite.StatusCode.Companion.InternalServerError
import klite.StatusCode.Companion.OK
import klite.StatusCode.Companion.ServiceUnavailable
import org.junit.jupiter.api.Test
import java.net.URI

/** The inbound route with `TURVIS_INTEGRATION_MODE=turvis` (#305b-1): Turvis is the authority, nothing is dispatched without a verify. */
class EDeliveryRoutesTurvisModeTest {
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

  private val routes = EDeliveryRoutes(keyManager, messageHandlers, generator, eDeliveryClient, partyRegistry, SignatureValidator(), turvis)

  private val conversation = "11111111-1111-1111-1111-111111111111"

  private fun verified(
    verifiedIdentity: String = "DE",
    from: String = "DE",
    conversationId: String? = conversation,
    payload: String = "<hello>world</hello>",
  ) = VerifiedInbound(
    TurvisEbmsRouting("m-1@x", conversationId, null, from, "EU-EE", "svc", "act"),
    TurvisSigner("CN=de", "ski", "01", verifiedIdentity),
    emptyList(), payload, "dec-1", "ver-1",
  )

  private fun rejection(code: String) = TurvisRejection("verifications", TurvisReason.of(code)?.httpStatus ?: 422, code, "aud-1", "turvis detail")

  @Test fun `verified message is dispatched by the verified identity and answered 200 with an empty body`() {
    every { turvis.process(any()) } returns verified()
    every { handler.invoke(any()) } returns null

    routes.msh(exchange)

    verify { exchange.send(OK, "", soap) }
    verify(timeout = 5000) {
      handler.invoke(match {
        it.key.receiverId == PartyId("DE") && it.key.senderId == PartyId("EU-EE") &&
          it.key.requestId.toString() == conversation && it.xml == "<hello>world</hello>"
      })
    }
    verify(exactly = 0) { generator.responseMessage(any()) }
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
    verify(exactly = 0) { exchange.send(OK, any<String>(), any()) }
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
    verify(exactly = 0) { exchange.send(OK, any<String>(), any()) }
  }

  @Test fun `the peer never sees the exception text of a failure`() {
    every { turvis.process(any()) } throws IllegalStateException("<driver>Jaan Tamm</driver> C:\\internal")
    val real = EDeliveryRoutes(keyManager, messageHandlers, EDeliveryMessageGenerator(mockk(relaxed = true)), eDeliveryClient, partyRegistry, SignatureValidator(), turvis)
    val sent = slot<String>()
    every { exchange.send(InternalServerError, capture(sent), soap) } just Runs

    real.msh(exchange)

    expect(sent.captured).notToContain("Jaan Tamm")
    expect(sent.captured).notToContain("internal")
    expect(sent.captured).toContain(FaultReason.OTHER.ebmsCode)
  }
}
