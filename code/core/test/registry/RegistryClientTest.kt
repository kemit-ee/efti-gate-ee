package registry

import edelivery.EDeliveryParty
import edelivery.PartyId
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import klite.Config
import klite.json.JsonMapper
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegistryClientTest {
  init {
    Config["REGISTRY_URL"] = "http://registry:8080"
  }

  private val http = mockk<HttpClient>()
  private val json = JsonMapper()
  private val client = RegistryClient(URI("http://registry:8080"), http, json)
  private val defaultClient = RegistryClient(http = http, jsonMapper = json)

  private fun stringResponse(status: Int = 200, body: String = "[]"): HttpResponse<String> = mockk {
    every { statusCode() } returns status
    every { body() } returns body
  }

  @Test fun `getGates maps gates to EDeliveryParty, keyed by id`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = """
      [{"id":"EU-EE","countryCode":"EE","eDeliveryUrl":"https://gate-ee.example/msh","eDeliveryCert":"cert-ee","tlsCert":"tls-ee","status":"ONLINE"},
       {"id":"EU-MOCK","countryCode":"EE","eDeliveryUrl":"https://gate-mock.example/msh","eDeliveryCert":"cert-mock","tlsCert":null,"status":"DISABLED"}]
    """.trimIndent())

    val gates = client.getGates()

    assertEquals(setOf(PartyId("EU-EE"), PartyId("EU-MOCK")), gates.keys)
    assertEquals(EDeliveryParty(PartyId("EU-EE"), URI("https://gate-ee.example/msh"), "cert-ee", "tls-ee"), gates[PartyId("EU-EE")])
    assertEquals(EDeliveryParty(PartyId("EU-MOCK"), URI("https://gate-mock.example/msh"), "cert-mock", null), gates[PartyId("EU-MOCK")])
  }

  @Test fun `getGates keys are case-insensitive party ids`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = """
      [{"id":"eu-ee","eDeliveryUrl":"https://gate-ee.example/msh","eDeliveryCert":"cert-ee","tlsCert":null,"status":"ONLINE"}]
    """.trimIndent())

    assertEquals(EDeliveryParty(PartyId("EU-EE"), URI("https://gate-ee.example/msh"), "cert-ee", null), client.getGates()[PartyId("EU-EE")])
  }

  @Test fun `reads the gates and platforms documents under REGISTRY_URL`() {
    val requestSlot = slot<HttpRequest>()
    every { http.send(capture(requestSlot), any<BodyHandler<String>>()) } returns stringResponse()

    defaultClient.getGates()
    assertEquals(URI("http://registry:8080/gates.json"), requestSlot.captured.uri())
    assertEquals("GET", requestSlot.captured.method())

    defaultClient.getPlatforms()
    assertEquals(URI("http://registry:8080/platforms.json"), requestSlot.captured.uri())
  }

  @Test fun `getPlatforms drops platforms without an eDeliveryCert`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = """
      [{"id":"mock-edelivery","baseUrl":"https://mock.example/api","eDeliveryCert":"cert","tlsCert":null,"status":"ONLINE","apiKey":null},
       {"id":"mock","baseUrl":"https://rest.example/api","eDeliveryCert":null,"tlsCert":null,"status":"ONLINE","apiKey":"secret"}]
    """.trimIndent())

    val platforms = client.getPlatforms()

    assertEquals(setOf(PartyId("mock-edelivery")), platforms.keys)
    assertEquals(EDeliveryParty(PartyId("mock-edelivery"), URI("https://mock.example/api"), "cert", null), platforms[PartyId("mock-edelivery")])
  }

  @Test fun `getParties merges gates and platforms`() {
    var call = 0
    every { http.send(any(), any<BodyHandler<String>>()) } answers {
      call++
      if (call == 1) stringResponse(body = """[{"id":"EU-EE","eDeliveryUrl":"https://gate.example/msh","eDeliveryCert":"gate-cert","tlsCert":null,"status":"ONLINE"}]""")
      else stringResponse(body = """[{"id":"mock-edelivery","baseUrl":"https://mock.example/api","eDeliveryCert":"platform-cert","tlsCert":null,"status":"ONLINE"}]""")
    }

    val parties = client.getParties()

    assertEquals(setOf(PartyId("EU-EE"), PartyId("mock-edelivery")), parties.keys)
    assertEquals("gate-cert", parties[PartyId("EU-EE")]?.eDeliveryCert)
    assertEquals("platform-cert", parties[PartyId("mock-edelivery")]?.eDeliveryCert)
  }

  @Test fun `an empty registry yields empty maps`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = "[]")

    assertEquals(emptyMap(), client.getGates())
    assertEquals(emptyMap(), client.getPlatforms())
    assertEquals(emptyMap(), client.getParties())
    assertNull(client.getPlatforms()[PartyId("anything")])
  }
}
