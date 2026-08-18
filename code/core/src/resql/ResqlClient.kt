package resql

import edelivery.EDeliveryParty
import edelivery.GateParty
import edelivery.PartyId
import edelivery.PlatformParty
import klite.Config
import klite.http.post
import klite.json.JsonMapper
import klite.json.parse
import klite.plus
import java.net.URI
import java.net.http.HttpClient

data class ResqlParams(
  val limit: Int = 9999,
  val offset: Int = 0
)

class ResqlClient(
  private val baseUrl: URI = URI(Config["RESQL_URL"]),
  private val http: HttpClient,
  private val jsonMapper: JsonMapper,
) {
  private inline fun <reified T> fetch(path: String) =
    jsonMapper.parse<List<T>>(http.post(baseUrl + path, jsonMapper.render(ResqlParams())).body())

  fun getGates() = fetch<GateParty>("/get_gates").map {
    EDeliveryParty(it.id, it.eDeliveryUrl, it.eDeliveryCert, it.tlsCert)
  }

  fun getPlatforms() = fetch<PlatformParty>("/get_platforms").mapNotNull { p ->
    p.eDeliveryCert?.let { EDeliveryParty(p.id, p.baseUrl, it, p.tlsCert) }
  }

  fun getParties(): Map<PartyId, EDeliveryParty> =
    (getGates() + getPlatforms()).associateBy { it.id }
}
