package resql

import edelivery.EDeliveryParty
import edelivery.GateParty
import edelivery.PartyId
import edelivery.PlatformParty
import klite.Config
import klite.http.post
import klite.json.JsonMapper
import klite.json.parse
import java.net.URI
import java.net.http.HttpClient

val Config.resqlUrl: URI get() = URI(get("RESQL_URL"))

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

  fun getGates() = fetch<GateParty>("/efti/get_gates").map {
    EDeliveryParty(it.id, it.eDeliveryUrl, it.eDeliveryCert, it.tlsCert)
  }

  fun getPlatforms() = fetch<PlatformParty>("/efti/get_platforms").mapNotNull { p ->
    p.eDeliveryCert?.let { EDeliveryParty(p.id, p.baseUrl, it, p.tlsCert) }
  }

  fun getParties(): Map<PartyId, EDeliveryParty> =
    (getGates() + getPlatforms()).associateBy { it.id }
}
