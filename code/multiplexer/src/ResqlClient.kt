import edelivery.EDeliveryParty
import edelivery.GateParty
import edelivery.PartyId
import klite.Config
import klite.http.post
import klite.json.JsonMapper
import klite.json.parse
import java.net.URI
import java.net.http.HttpClient

data class ResqlParams(
  val limit: Int = 9999,
  val offset: Int = 0
)

class ResqlClient(
  private val http: HttpClient,
  private val jsonMapper: JsonMapper,
) {
  private inline fun <reified T> fetch(path: String) =
    jsonMapper.parse<List<T>>(http.post(URI(Config["RESQL_URL"]).resolve(path), jsonMapper.render(ResqlParams())).body())

  fun getParties(): Map<PartyId, EDeliveryParty> {
    val parties = fetch<GateParty>("/efti/get_gates").map {
      EDeliveryParty(it.id, it.eDeliveryUrl, it.eDeliveryCert, it.tlsCert)
    }

    return parties.associateBy { it.id }
  }
}