import edelivery.EDeliveryParty
import edelivery.PartyId
import klite.http.get
import klite.info
import klite.json.JsonMapper
import klite.json.parse
import klite.logger
import java.net.URI
import java.net.http.HttpClient

data class RuuterResponse<T>(
  val response: T
)

data class GateParty(
  val id: PartyId,
  val eDeliveryUrl: URI,
  val eDeliveryCert: String,
  val tlsCert: String?
)

data class PlatformParty(
  val id: PartyId,
  val baseUrl: URI,
  val eDeliveryCert: String?,
  val tlsCert: String?
)

/** Forwards raw EFTI XMLs to Ruuter for further conversion */
class RuuterClient(
  private val http: HttpClient,
  private val jsonMapper: JsonMapper,
) {
  private val log = logger()
  private val baseUrl = URI("http://ruuter:8086")

  fun getParties(): Map<PartyId, EDeliveryParty> {
    val gates = jsonMapper.parse<RuuterResponse<List<GateParty>>>(http.get(baseUrl.resolve("/efti/api/v1/gates")).body()).response
    val platforms = jsonMapper.parse<RuuterResponse<List<PlatformParty>>>(http.get(baseUrl.resolve("/efti/api/v1/platforms")).body()).response

    val eDeliveryParties = gates.map { g -> EDeliveryParty(g.id, g.eDeliveryUrl, g.eDeliveryCert, g.tlsCert) } +
      platforms.filter { p -> p.eDeliveryCert != null }.map { p -> EDeliveryParty(p.id, p.baseUrl, p.eDeliveryCert!!, p.tlsCert) }
    eDeliveryParties.forEach { log.info("Initialized ${it.id} in eDelivery registry") }
    return eDeliveryParties.associateBy { it.id }
  }

  fun saveConsignment(xml: String /* FTI004UploadIdentifierRequest */): String {
    TODO()
  }

  fun searchConsignments(xml: String /* FTI019SearchIdentifierRequest */): String {
    TODO()
  }

  fun getDataset(xml: String /* FTI009GetCmdsRequest */): String {
    TODO()
  }

  fun followUp(xml: String /* FTI025LodgeFollowUpCommRequest */): String {
    TODO()
  }
}
