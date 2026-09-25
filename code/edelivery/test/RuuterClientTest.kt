import edelivery.PartyId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import klite.Config
import klite.http.HttpException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.util.UUID

private class TestableRuuterClient(http: HttpClient): RuuterClient(http) {
  fun tokenForTest() = internalServiceToken
}

class RuuterClientTest {
  init {
    Config["RUUTER_URL"] = "http://ruuter:8086"
    Config["INTERNAL_SERVICE_TOKEN"] = "default-service-token"
  }

  private val http = mockk<HttpClient>()
  private val client = RuuterClient(http, URI("http://ruuter:8086"), "test-service-token")
  private val requestId = UUID.randomUUID()

  private fun stub(status: Int = 200, body: String = "<Response/>") {
    val response = mockk<HttpResponse<String>>(relaxed = true) {
      every { statusCode() } returns status
      every { body() } returns body
    }
    every { http.send(any(), any<BodyHandler<String>>()) } returns response
  }

  @Test fun `defaults baseUrl and internalServiceToken from Config`() {
    val defaultClient = TestableRuuterClient(http)
    assertEquals("default-service-token", defaultClient.tokenForTest())
  }

  @Test fun `upload forwards inbound platform identity with the service credential`() {
    stub(201, "<FTI029UploadIdentifierResponse/>")

    client.saveConsignment("<FTI004UploadIdentifierRequest/>", requestId, PartyId("platform-one"))

    verify {
      http.send(match<HttpRequest> {
        it.uri().path == "/platforms/v1/consignments-xml" &&
          it.headers().firstValue("X-Platform-Id").orElse("") == "platform-one" &&
          it.headers().firstValue("X-Internal-Service-Token").orElse("") == "test-service-token" &&
          it.headers().firstValue("x-request-id").orElse("") == requestId.toString()
      }, any<BodyHandler<String>>())
    }
  }

  @Test fun `searchConsignments sends to the gate-scoped search endpoint with no platform id header`() {
    stub(body = "<ParameterIDSetCriteria/>")

    val result = client.searchConsignments("<FTI019SearchIdentifierRequest/>", PartyId("EU-EE"), requestId)

    assertEquals("<ParameterIDSetCriteria/>", result)
    verify {
      http.send(match<HttpRequest> {
        it.uri().toString().endsWith("/efti/api/v1/consignments/search-xml?gateId=EU-EE") &&
          it.headers().firstValue("X-Platform-Id").isEmpty
      }, any<BodyHandler<String>>())
    }
  }

  @Test fun `getDataset sends to the dataset endpoint`() {
    stub(body = "<FTI010GetCmdsResponse/>")

    val result = client.getDataset("<FTI009GetCmdsRequest/>", requestId, PartyId("EU-EE"))

    assertEquals("<FTI010GetCmdsResponse/>", result)
    verify { http.send(match<HttpRequest> { it.uri().path == "/efti/api/v1/dataset-xml" }, any<BodyHandler<String>>()) }
  }

  @Test fun `followUp sends to the follow-up endpoint`() {
    stub(body = "<FTI030LodgeFollowUpCommResponse/>")

    val result = client.followUp("<FTI025LodgeFollowUpCommRequest/>", requestId, PartyId("EU-EE"))

    assertEquals("<FTI030LodgeFollowUpCommResponse/>", result)
    verify { http.send(match<HttpRequest> { it.uri().path == "/efti/api/v1/follow-up-xml" }, any<BodyHandler<String>>()) }
  }

  @Test fun `a non-2xx response throws instead of returning the error body`() {
    stub(status = 502, body = "upstream unavailable")

    assertThrows<HttpException> {
      client.getDataset("<FTI009GetCmdsRequest/>", requestId, PartyId("EU-EE"))
    }
  }
}
