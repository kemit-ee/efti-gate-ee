import klite.Config
import klite.http.post
import klite.plus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse

/** Forwards raw EFTI XMLs to Ruuter for further conversion */
class RuuterClient(
  private val http: HttpClient,
  private val baseUrl: URI = URI(Config["RUUTER_URL"]),
) {
  fun saveConsignment(xml: String /* FTI004UploadIdentifierRequest */) =
    http.post(baseUrl + "/consignments/xml", xml) { header("Content-Type", "text/xml") }.checkBody()

  fun searchConsignments(xml: String /* FTI019SearchIdentifierRequest */): String {
    TODO()
  }

  fun getDataset(xml: String /* FTI009GetCmdsRequest */): String {
    TODO()
  }

  fun followUp(xml: String /* FTI025LodgeFollowUpCommRequest */): String {
    TODO()
  }

  fun HttpResponse<String>.checkBody(): String {
    if (statusCode() >= 300) error("Failed with ${statusCode()}")
    return body()
  }
}
