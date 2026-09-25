package resql

import edelivery.EDeliveryParty
import edelivery.PartyId
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import klite.json.JsonMapper
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResqlClientTest {
  private val baseUrl = URI("http://resql:8090/efti")
  private val http = mockk<HttpClient>()
  private val json = JsonMapper()
  private val client = ResqlClient(baseUrl, http, json)

  private fun stringResponse(status: Int = 200, body: String = "[]"): HttpResponse<String> = mockk {
    every { statusCode() } returns status
    every { body() } returns body
  }

  @Test fun `getGates maps rows to EDeliveryParty, keyed by id`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = """
      [{"id":"EE","eDeliveryUrl":"https://gate-ee.example/msh","eDeliveryCert":"cert-ee","tlsCert":"tls-ee"},
       {"id":"EU-MOCK","eDeliveryUrl":"https://gate-mock.example/msh","eDeliveryCert":"cert-mock","tlsCert":null}]
    """.trimIndent())

    val gates = client.getGates()

    assertEquals(setOf(PartyId("EE"), PartyId("EU-MOCK")), gates.keys)
    assertEquals(EDeliveryParty(PartyId("EE"), URI("https://gate-ee.example/msh"), "cert-ee", "tls-ee"), gates[PartyId("EE")])
    assertEquals(EDeliveryParty(PartyId("EU-MOCK"), URI("https://gate-mock.example/msh"), "cert-mock", null), gates[PartyId("EU-MOCK")])
  }

  @Test fun `getGates forwards the status filter and default pagination`() {
    val requestSlot = slot<HttpRequest>()
    every { http.send(capture(requestSlot), any<BodyHandler<String>>()) } returns stringResponse()

    client.getGates(status = "ACTIVE")

    assertEquals(URI("$baseUrl/get_gates"), requestSlot.captured.uri())
    val body = requestSlot.captured.bodyPublisher().get().let { publisher ->
      val sink = StringBuilder()
      publisher.subscribe(object : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        override fun onSubscribe(s: java.util.concurrent.Flow.Subscription) = s.request(Long.MAX_VALUE)
        override fun onNext(item: java.nio.ByteBuffer) { sink.append(Charsets.UTF_8.decode(item)) }
        override fun onError(t: Throwable) {}
        override fun onComplete() {}
      })
      sink.toString()
    }
    assertEquals(json.render(ResqlParams(status = "ACTIVE")), body)
  }

  @Test fun `getPlatforms drops platforms without an eDeliveryCert`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = """
      [{"id":"mock","baseUrl":"https://mock.example/api","eDeliveryCert":"cert","tlsCert":null},
       {"id":"rest-only","baseUrl":"https://rest.example/api","eDeliveryCert":null,"tlsCert":null}]
    """.trimIndent())

    val platforms = client.getPlatforms()

    assertEquals(setOf(PartyId("mock")), platforms.keys)
    assertEquals(EDeliveryParty(PartyId("mock"), URI("https://mock.example/api"), "cert", null), platforms[PartyId("mock")])
  }

  @Test fun `getParties merges gates and platforms`() {
    var call = 0
    every { http.send(any(), any<BodyHandler<String>>()) } answers {
      call++
      if (call == 1) stringResponse(body = """[{"id":"EE","eDeliveryUrl":"https://gate.example/msh","eDeliveryCert":"gate-cert","tlsCert":null}]""")
      else stringResponse(body = """[{"id":"mock","baseUrl":"https://mock.example/api","eDeliveryCert":"platform-cert","tlsCert":null}]""")
    }

    val parties = client.getParties()

    assertEquals(setOf(PartyId("EE"), PartyId("mock")), parties.keys)
    assertEquals("gate-cert", parties[PartyId("EE")]?.eDeliveryCert)
    assertEquals("platform-cert", parties[PartyId("mock")]?.eDeliveryCert)
  }

  @Test fun `getPlatforms returns an empty map when there are no platforms`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = "[]")

    assertEquals(emptyMap(), client.getPlatforms())
    assertNull(client.getPlatforms()[PartyId("anything")])
  }
}
