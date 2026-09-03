package resql

import edelivery.EDeliveryParty
import edelivery.GateParty
import edelivery.PartyId
import edelivery.PlatformParty
import klite.Config
import klite.http.bodyOrThrow
import klite.http.post
import klite.info
import klite.json.JsonMapper
import klite.json.parse
import klite.logger
import klite.plus
import java.net.URI
import java.net.http.HttpClient

data class ResqlParams(
  val status: String = "ONLINE",
  val limit: String = "9999",
  val offset: String = "0"
)

class ResqlClient(
  private val baseUrl: URI = URI(Config["RESQL_URL"] + "/efti"),
  private val http: HttpClient,
  private val jsonMapper: JsonMapper,
) {
  private val log = logger()

  private inline fun <reified T> fetch(path: String, params: ResqlParams = ResqlParams()): List<T> {
    val res = http.post(baseUrl + path, jsonMapper.render(params))
    return jsonMapper.parse<List<T>>(res.bodyOrThrow())
  }

  fun getOnlineGates() = fetch<GateParty>("/get_gates").map {
    EDeliveryParty(it.id, it.eDeliveryUrl, it.eDeliveryCert, it.tlsCert)
  }.associateBy { it.id }.also { log.info("Fetched gates: ${it.keys}") }

  fun getOnlinePlatforms() = fetch<PlatformParty>("/get_platforms").mapNotNull { p ->
    p.eDeliveryCert?.let { EDeliveryParty(p.id, p.baseUrl, it, p.tlsCert) }
  }.associateBy { it.id }.also { log.info("Fetched platforms: ${it.keys}") }

  fun getParties(): Map<PartyId, EDeliveryParty> = getOnlineGates() + getOnlinePlatforms()
}
