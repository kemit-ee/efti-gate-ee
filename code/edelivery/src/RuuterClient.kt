import klite.Config
import klite.StatusCode
import klite.http.HttpException
import klite.http.post
import klite.plus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse

/** Forwards raw EFTI XMLs to Ruuter for further conversion */
class RuuterClient(
  private val http: HttpClient,
  private val baseUrl: URI = URI(Config["RUUTER_M2M_URL"] + "/m2m/edelivery/v1"),
) {
  fun saveConsignment(xml: String /* FTI004UploadIdentifierRequest */) =
    http.sendXml(baseUrl + "/consignments-xml", xml)

  fun searchConsignments(xml: String /* FTI019SearchIdentifierRequest */) =
    http.sendXml(baseUrl + "/consignments-search-xml", xml)

  fun getDataset(xml: String /* FTI009GetCmdsRequest */) =
    http.sendXml(baseUrl + "/dataset-xml", xml)

  fun getLocalDataset(xml: String /* FTI009GetCmdsRequest */) =
    http.sendXml(baseUrl + "/dataset-local", xml)

  fun followUp(xml: String /* FTI025LodgeFollowUpCommRequest */) =
    http.sendXml(baseUrl + "/follow-up-xml", xml)

  fun localFollowUp(xml: String /* FTI025LodgeFollowUpCommRequest */) =
    http.sendXml(baseUrl + "/follow-up-local", xml)

  private fun HttpClient.sendXml(url: URI, xml: String) =
    post(url, xml) { header("Content-Type", "text/xml") }.checkBody()

  private fun HttpResponse<String>.checkBody(): String {
    if (statusCode() >= 300) throw HttpException(StatusCode(statusCode()), body())
    return body()
  }
}
