import edelivery.PartyId
import klite.Config
import klite.http.bodyOrThrow
import klite.http.post
import klite.plus
import java.net.URI
import java.net.http.HttpClient

/** Forwards raw EFTI XMLs to Ruuter for further processing */
class RuuterClient(
  private val http: HttpClient,
  private val baseUrl: URI = URI(Config["RUUTER_URL"]),
) {
  fun saveConsignment(xml: String /* FTI004UploadIdentifierRequest */) =
    http.sendXml(baseUrl + "/platforms/v1/consignments-xml", xml)

  fun searchConsignments(xml: String /* FTI019SearchIdentifierRequest */, gateId: PartyId) =
    http.sendXml(baseUrl + "/efti/api/v1/consignments/search-xml?gateId=$gateId", xml)

  fun getDataset(xml: String /* FTI009GetCmdsRequest */) =
    http.sendXml(baseUrl + "/efti/api/v1/dataset-xml", xml)

  fun getLocalDataset(xml: String /* FTI009GetCmdsRequest */) =
    http.sendXml(baseUrl + "/efti/api/v1/dataset-local", xml)

  fun followUp(xml: String /* FTI025LodgeFollowUpCommRequest */) =
    http.sendXml(baseUrl + "/efti/api/v1/follow-up-xml", xml)

  fun localFollowUp(xml: String /* FTI025LodgeFollowUpCommRequest */) =
    http.sendXml(baseUrl + "/efti/api/v1/follow-up-local", xml)

  private fun HttpClient.sendXml(url: URI, xml: String) =
    post(url, xml) { header("Content-Type", "text/xml") }.bodyOrThrow()
}
