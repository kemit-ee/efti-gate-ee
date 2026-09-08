import edelivery.PartyId
import klite.Config
import klite.http.bodyOrThrow
import klite.http.get
import klite.plus
import java.net.URI
import java.net.http.HttpClient
import java.util.UUID

/* Dev implementation of ruuter client. allows making queries to mock edelivery platform */
class RuuterClientDev(
  private val http: HttpClient,
  private val baseUrl: URI = URI(Config["RUUTER_URL"]),
  internalServiceToken: String = Config["INTERNAL_SERVICE_TOKEN"],
): RuuterClient(http, baseUrl, internalServiceToken) {
  override fun getDataset(xml: String, requestId: UUID, receiverId: PartyId): String {
    if (receiverId.value == "mock-edelivery") {
      return http.get(baseUrl + "/mock-platform/v1/dataset-edelivery/") {
        header("Content-Type", "text/xml")
        header("x-api-key", "mock-secret-key")
        header("x-request-id", requestId.toString())
      }.bodyOrThrow()
    }

    return super.getDataset(xml, requestId, receiverId)
  }
}
