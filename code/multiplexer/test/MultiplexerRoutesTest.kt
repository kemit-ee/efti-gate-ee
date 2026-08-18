import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import edelivery.EDeliveryParty
import edelivery.PartyId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import klite.Cache
import klite.Config
import klite.HttpExchange
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.*

class MultiplexerRoutesTest {
  init {
    Config["EDELIVERY_URL"] = "http://localhost:8081"
  }

  val party1 = EDeliveryParty(PartyId("party-1"), URI("http://p1"), "cert1")
  val party2 = EDeliveryParty(PartyId("party-2"), URI("http://p2"), "cert2")

  val registry = mockk<PartyRegistry> {
    every { parties } returns mapOf(party1.id to party1, party2.id to party2)
  }
  val http = mockk<HttpClient>()
  val exchange = mockk<HttpExchange>(relaxed = true)
  val routes = MultiplexerRoutes(registry, http)

  val xml = "<test>data</test>"

  @Test fun multiplexReturnsFirstResponse() {
    val searchId = UUID.randomUUID()
    every { http.send(match<HttpRequest> { it.uri().toString().contains("/send/party-1") }, any<HttpResponse.BodyHandler<String>>()) } returns
      mockk<HttpResponse<String>>(relaxed = true) { every { statusCode() } returns 200; every { body() } returns "<response>1</response>" }
    every { http.send(match<HttpRequest> { it.uri().toString().contains("/send/party-2") }, any<HttpResponse.BodyHandler<String>>()) } returns
      mockk<HttpResponse<String>>(relaxed = true) { every { statusCode() } returns 204; every { body() } returns "" }

    val result = routes.multiplex(xml, searchId)

    expect(result).toEqual("<response>1</response>")
    verify {
      http.send(match<HttpRequest> { it.uri().toString().contains("/send/party-1") }, any<HttpResponse.BodyHandler<String>>())
      http.send(match<HttpRequest> { it.uri().toString().contains("/send/party-2") }, any<HttpResponse.BodyHandler<String>>())
    }
  }

  @Test fun multiplexCollectsMultipleResponses() {
    val searchId = UUID.randomUUID()
    every { http.send(match<HttpRequest> { it.uri().toString().contains("/send/party-1") }, any<HttpResponse.BodyHandler<String>>()) } returns
      mockk<HttpResponse<String>>(relaxed = true) { every { statusCode() } returns 200; every { body() } returns "<r1/>" }
    every { http.send(match<HttpRequest> { it.uri().toString().contains("/send/party-2") }, any<HttpResponse.BodyHandler<String>>()) } returns
      mockk<HttpResponse<String>>(relaxed = true) { every { statusCode() } returns 200; every { body() } returns "<r2/>" }

    val result = routes.multiplex(xml, searchId)

    assert(result == "<r1/>" || result == "<r2/>") { "Expected <r1/> or <r2/> but was $result" }
  }

  @Test fun restReturnsEmptyWhenSearchIdNotFound() {
    val result = routes.rest(UUID.randomUUID(), exchange)

    expect(result).toEqual("")
    verify { exchange.header("complete", "true") }
  }

  @Test fun restDrainsQueuedResponses() {
    val searchId = UUID.randomUUID()
    val responses = PartyResponses()
    responses.xmls.offer("<r1/>")
    responses.xmls.offer("<r2/>")
    responses.complete = true
    routes.pending(searchId, responses)

    val result = routes.rest(searchId, exchange)

    expect(result).toEqual("<r1/>⦀<r2/>")
    verify { exchange.header("complete", "true") }
  }

  private fun MultiplexerRoutes.pending(key: UUID, value: PartyResponses) {
    val field = MultiplexerRoutes::class.java.getDeclaredField("pending")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val cache = field.get(this) as Cache<UUID, PartyResponses>
    cache[key] = value
  }
}
