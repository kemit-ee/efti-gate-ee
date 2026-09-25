import edelivery.PartyId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import klite.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.util.UUID

class RuuterClientDevTest {
  init {
    Config["RUUTER_URL"] = "http://ruuter:8086"
    Config["INTERNAL_SERVICE_TOKEN"] = "default-service-token"
  }

  private val http = mockk<HttpClient>()
  private val client = RuuterClientDev(http, URI("http://ruuter:8086"), "test-service-token")
  private val requestId = UUID.randomUUID()

  @Test fun `defaults baseUrl and internalServiceToken from Config`() {
    RuuterClientDev(http)
    // no exception -- all constructor params resolve from Config
  }

  private fun stub(body: String) {
    val response = mockk<HttpResponse<String>>(relaxed = true) {
      every { statusCode() } returns 200
      every { body() } returns body
    }
    every { http.send(any(), any<BodyHandler<String>>()) } returns response
  }

  @Test fun `getDataset routes mock-edelivery to the mock platform instead of Ruuter`() {
    stub("<FTI010GetCmdsResponse/>")

    val result = client.getDataset("<FTI009GetCmdsRequest/>", requestId, PartyId("mock-edelivery"))

    assertEquals("<FTI010GetCmdsResponse/>", result)
    verify {
      http.send(match<HttpRequest> {
        it.uri().toString().endsWith("/mock-platform/v1/dataset-edelivery") &&
          it.headers().firstValue("x-api-key").orElse("") == "mock-secret-key"
      }, any<BodyHandler<String>>())
    }
  }

  @Test fun `getDataset delegates to RuuterClient for any other receiver`() {
    stub("<FTI010GetCmdsResponse/>")

    client.getDataset("<FTI009GetCmdsRequest/>", requestId, PartyId("EU-EE"))

    verify { http.send(match<HttpRequest> { it.uri().path == "/efti/api/v1/dataset-xml" }, any<BodyHandler<String>>()) }
  }

  @Test fun `followUp routes mock-edelivery to the mock platform instead of Ruuter`() {
    stub("<FTI030LodgeFollowUpCommResponse/>")

    val result = client.followUp("<FTI025LodgeFollowUpCommRequest/>", requestId, PartyId("mock-edelivery"))

    assertEquals("<FTI030LodgeFollowUpCommResponse/>", result)
    verify {
      http.send(match<HttpRequest> {
        it.uri().toString().endsWith("/mock-platform/v1/dataset-edelivery/$requestId/follow-up") &&
          it.headers().firstValue("x-api-key").orElse("") == "mock-secret-key"
      }, any<BodyHandler<String>>())
    }
  }

  @Test fun `followUp delegates to RuuterClient for any other receiver`() {
    stub("<FTI030LodgeFollowUpCommResponse/>")

    client.followUp("<FTI025LodgeFollowUpCommRequest/>", requestId, PartyId("EU-EE"))

    verify { http.send(match<HttpRequest> { it.uri().path == "/efti/api/v1/follow-up-xml" }, any<BodyHandler<String>>()) }
  }
}
