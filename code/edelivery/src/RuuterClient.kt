import edelivery.PartyId
import klite.Config
import klite.http.bodyOrThrow
import klite.http.post
import klite.plus
import java.net.URI
import java.net.http.HttpClient
import java.util.*

/** Forwards raw EFTI XMLs to Ruuter for further processing */
class RuuterClient(
  private val http: HttpClient,
  private val baseUrl: URI = URI(Config["RUUTER_URL"]),
  private val internalServiceToken: String = Config["INTERNAL_SERVICE_TOKEN"],
) {
  fun saveConsignment(xml: String, requestId: UUID /* FTI004UploadIdentifierRequest */) =
    http.sendXml(baseUrl + "/platforms/v1/consignments-xml", xml, requestId)

  fun searchConsignments(xml: String, gateId: PartyId, requestId: UUID /* FTI019SearchIdentifierRequest */) =
    http.sendXml(baseUrl + "/efti/api/v1/consignments/search-xml?gateId=$gateId", xml, requestId)

  fun getDataset(xml: String, requestId: UUID /* FTI009GetCmdsRequest */) =
    http.sendXml(baseUrl + "/efti/api/v1/dataset-xml", xml, requestId)

  fun followUp(xml: String, requestId: UUID /* FTI025LodgeFollowUpCommRequest */) =
    http.sendXml(baseUrl + "/efti/api/v1/follow-up-xml", xml, requestId)

  // efti/api/v1/* is gate-internal only (DSL/Ruuter/efti/POST/api/v1/.guard.yml) — every
  // call needs the shared service token. /platforms/v1/consignments-xml is guarded
  // separately (platform X-Api-Key) and ignores this header.
  private fun HttpClient.sendXml(url: URI, xml: String, requestId: UUID) =
    post(url, xml) {
      header("Content-Type", "text/xml")
      header("X-Internal-Service-Token", internalServiceToken)
      header("x-request-id", requestId.toString())
    }.bodyOrThrow()
}
