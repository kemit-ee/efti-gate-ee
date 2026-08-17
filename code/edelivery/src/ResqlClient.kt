import edelivery.EDeliveryParty
import edelivery.PartyId
import klite.Config
import klite.http.post
import klite.json.JsonMapper
import klite.json.parse
import java.net.URI
import java.net.http.HttpClient

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

data class ResqlParams(
  val limit: Int = 9999,
  val offset: Int = 0
)

class ResqlClient(
  private val http: HttpClient,
  private val jsonMapper: JsonMapper,
) {
  private inline fun <reified T> fetch(path: String) =
    jsonMapper.parse<List<T>>(http.post(Config.resqlUrl.resolve(path), jsonMapper.render(ResqlParams())).body())

  fun getParties(): Map<PartyId, EDeliveryParty> {
    val parties = fetch<GateParty>("/efti/get_gates").map {
      EDeliveryParty(it.id, it.eDeliveryUrl, it.eDeliveryCert, it.tlsCert)
    } + fetch<PlatformParty>("/efti/get_platforms").mapNotNull { p ->
      p.eDeliveryCert?.let { EDeliveryParty(p.id, p.baseUrl, it, p.tlsCert) }
    }

    return parties.associateBy { it.id }
  }
}