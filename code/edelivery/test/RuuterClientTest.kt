import edelivery.PartyId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.util.UUID

class RuuterClientTest {
  @Test fun `upload forwards inbound platform identity with the service credential`() {
    val http = mockk<HttpClient>()
    val response = mockk<HttpResponse<String>>(relaxed = true) {
      every { statusCode() } returns 201
      every { body() } returns "<FTI029UploadIdentifierResponse/>"
    }
    every { http.send(any(), any<BodyHandler<String>>()) } returns response
    val client = RuuterClient(http, URI("http://ruuter:8086"), "test-service-token")
    val requestId = UUID.randomUUID()

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
}
