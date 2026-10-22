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
import registryTimeout
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

  @Test fun `getGates maps ONLINE gates to EDeliveryParty, keyed by id`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = """
      [{"id":"EU-EE","countryCode":"EE","eDeliveryUrl":"https://gate-ee.example/msh","eDeliveryCert":"cert-ee","tlsCert":"tls-ee","status":"ONLINE"},
       {"id":"EU-MOCK","countryCode":"EE","eDeliveryUrl":"https://gate-mock.example/msh","eDeliveryCert":"cert-mock","status":"ONLINE"}]
    """.trimIndent())

    val gates = client.getGates()

    assertEquals(setOf(PartyId("EU-EE"), PartyId("EU-MOCK")), gates.keys)
    assertEquals(EDeliveryParty(PartyId("EU-EE"), URI("https://gate-ee.example/msh"), "cert-ee", "tls-ee"), gates[PartyId("EU-EE")])
    assertEquals(EDeliveryParty(PartyId("EU-MOCK"), URI("https://gate-mock.example/msh"), "cert-mock", null), gates[PartyId("EU-MOCK")])
  }

  @Test fun `getGates excludes DISABLED gates and gates without an eDeliveryCert`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = """
      [{"id":"EU-EE","eDeliveryUrl":"https://gate-ee.example/msh","eDeliveryCert":"cert-ee","status":"ONLINE"},
       {"id":"EU-OFF","eDeliveryUrl":"https://gate-off.example/msh","eDeliveryCert":"cert-off","status":"DISABLED"},
       {"id":"EU-NOCERT","eDeliveryUrl":"https://gate-nocert.example/msh","status":"ONLINE"}]
    """.trimIndent())

    assertEquals(setOf(PartyId("EU-EE")), client.getGates().keys)
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

  @Test fun `getPlatforms excludes DISABLED platforms`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = """
      [{"id":"mock-edelivery","baseUrl":"https://mock.example/api","eDeliveryCert":"cert","status":"ONLINE"},
       {"id":"off-edelivery","baseUrl":"https://off.example/api","eDeliveryCert":"cert","status":"DISABLED"}]
    """.trimIndent())

    assertEquals(setOf(PartyId("mock-edelivery")), client.getPlatforms().keys)
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

  @Test fun `every registry request carries the registry timeout`() {
    val requestSlot = slot<HttpRequest>()
    every { http.send(capture(requestSlot), any<BodyHandler<String>>()) } returns stringResponse()

    client.getGates()

    assertEquals(java.time.Duration.ofSeconds(registryTimeout.inWholeSeconds), requestSlot.captured.timeout().get())
  }

  @Test fun `a transient network failure is retried and then succeeds`() {
    var calls = 0
    every { http.send(any(), any<BodyHandler<String>>()) } answers {
      if (calls++ == 0) throw java.io.IOException("connection reset") else stringResponse()
    }

    assertEquals(emptyMap(), client.getGates())
    assertEquals(2, calls)
  }

  @Test fun `an HTTP error status is not retried`() {
    var calls = 0
    every { http.send(any(), any<BodyHandler<String>>()) } answers { calls++; stringResponse(status = 500, body = "boom") }

    kotlin.test.assertFailsWith<klite.http.HttpException> { client.getGates() }
    assertEquals(1, calls)
  }
}

