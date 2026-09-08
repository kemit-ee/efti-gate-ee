import edelivery.PartyId
import klite.Config
import klite.http.bodyOrThrow
import klite.http.post
import klite.plus
import java.net.URI
import java.net.http.HttpClient
import java.util.*

/** Forwards raw EFTI XMLs to Ruuter for further processing */
open class RuuterClient(
  protected val http: HttpClient,
  protected val baseUrl: URI = URI(Config["RUUTER_URL"]),
  protected val internalServiceToken: String = Config["INTERNAL_SERVICE_TOKEN"],
) {
  fun saveConsignment(xml: String, requestId: UUID /* FTI004UploadIdentifierRequest */) =
    http.sendXml(baseUrl + "/platforms/v1/consignments-xml", xml, requestId)

  fun searchConsignments(xml: String, gateId: PartyId, requestId: UUID /* FTI019SearchIdentifierRequest */): String =
    http.sendXml(baseUrl + "/efti/api/v1/consignments/search-xml?gateId=$gateId", xml, requestId)

  open fun getDataset(xml: String, requestId: UUID /* FTI009GetCmdsRequest */, receiverId: PartyId) =
    http.sendXml(baseUrl + "/efti/api/v1/dataset-xml", xml, requestId)

  open fun followUp(xml: String, requestId: UUID /* FTI025LodgeFollowUpCommRequest */, receiverId: PartyId) =
    http.sendXml(baseUrl + "/efti/api/v1/follow-up-xml", xml, requestId)

  // efti/api/v1/* is gate-internal only (DSL/Ruuter/efti/POST/api/v1/.guard.yml) — every
  // call needs the shared service token. /platforms/v1/consignments-xml is guarded
  // separately (platform X-Api-Key) and ignores this header.
  fun HttpClient.sendXml(url: URI, xml: String, requestId: UUID) =
    post(url, xml) {
      header("Content-Type", "text/xml")
      header("X-Internal-Service-Token", internalServiceToken)
      header("x-request-id", requestId.toString())
    }.bodyOrThrow()
}
