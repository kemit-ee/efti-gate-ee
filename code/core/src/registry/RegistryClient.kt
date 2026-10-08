package registry

import edelivery.EDeliveryParty
import edelivery.PartyId
import klite.Config
import klite.http.bodyOrThrow
import klite.http.get
import klite.info
import klite.json.JsonMapper
import klite.json.parse
import klite.logger
import klite.plus
import java.net.URI
import java.net.http.HttpClient

/** Reads the gates/platforms served as JSON by the internal registry service (ADR-011). */
class RegistryClient(
  private val baseUrl: URI = URI(Config["REGISTRY_URL"]),
  private val http: HttpClient,
  private val jsonMapper: JsonMapper,
) {
  private val log = logger()

  private inline fun <reified T> fetch(path: String): List<T> =
    jsonMapper.parse(http.get(baseUrl + path).bodyOrThrow())

  fun getGates() = fetch<RegistryGate>("/gates.json").map {
    EDeliveryParty(it.id, it.eDeliveryUrl, it.eDeliveryCert, it.tlsCert)
  }.associateBy { it.id }.also { log.info("Fetched gates: ${it.keys}") }

  fun getPlatforms() = fetch<RegistryPlatform>("/platforms.json").mapNotNull { p ->
    p.eDeliveryCert?.let { EDeliveryParty(p.id, p.baseUrl, it, p.tlsCert) }
  }.associateBy { it.id }.also { log.info("Fetched platforms: ${it.keys}") }

  fun getParties(): Map<PartyId, EDeliveryParty> = getGates() + getPlatforms()
}

data class RegistryGate(
  val id: PartyId,
  val eDeliveryUrl: URI,
  val eDeliveryCert: String,
  val tlsCert: String? = null,
)

data class RegistryPlatform(
  val id: PartyId,
  val baseUrl: URI,
  val eDeliveryCert: String? = null,
  val tlsCert: String? = null,
)
