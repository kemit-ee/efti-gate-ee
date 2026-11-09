package registry

import edelivery.EDeliveryParty
import edelivery.PartyId
import klite.Config
import bodyOrThrowStatusOnly
import klite.http.get
import klite.http.timeout
import klite.info
import klite.json.JsonMapper
import klite.json.parse
import klite.logger
import klite.plus
import klite.warn
import registryTimeout
import retryIdempotent
import java.net.URI
import java.net.http.HttpClient

/**
 * Reads the gates/platforms served as JSON by the internal registry service (ADR-011).
 * Only ONLINE entries become AS4 parties: a DISABLED gate or platform can neither send to nor
 * receive from this gate, matching the DSL guards.
 */
class RegistryClient(
  private val baseUrl: URI = URI(Config["REGISTRY_URL"]),
  private val http: HttpClient,
  private val jsonMapper: JsonMapper,
) {
  private val log = logger()

  private inline fun <reified T> fetch(path: String): List<T> =
    jsonMapper.parse(retryIdempotent("registry GET $path") { http.get(baseUrl + path) { timeout(registryTimeout) }.bodyOrThrowStatusOnly() })

  fun getGates() = fetch<RegistryGate>("/gates.json").filter { it.status == ONLINE }.mapNotNull { g ->
    if (g.eDeliveryCert == null) log.warn("Gate ${g.id} has no eDeliveryCert, skipped")
    g.eDeliveryCert?.let { EDeliveryParty(g.id, g.eDeliveryUrl, it, g.tlsCert) }
  }.associateBy { it.id }.also { log.info("Fetched gates: ${it.keys}") }

  fun getPlatforms() = fetch<RegistryPlatform>("/platforms.json").filter { it.status == ONLINE }.mapNotNull { p ->
    p.eDeliveryCert?.let { EDeliveryParty(p.id, p.baseUrl, it, p.tlsCert) }
  }.associateBy { it.id }.also { log.info("Fetched platforms: ${it.keys}") }

  fun getParties(): Map<PartyId, EDeliveryParty> = getGates() + getPlatforms()

  /** The generation of the registry artifact (`trustViewGeneration` in health.json), or null when it cannot be read or is malformed. */
  fun getGeneration(): String? = try {
    val health = jsonMapper.parse<Map<String, Any?>>(retryIdempotent("registry GET /health.json") { http.get(baseUrl + "/health.json") { timeout(registryTimeout) }.bodyOrThrowStatusOnly() })
    TrustGeneration.parse(health["trustViewGeneration"])
  } catch (e: Exception) {
    log.warn("Registry generation unreadable: ${e.javaClass.simpleName}") // pii-ok: exception class only
    null
  }

  companion object {
    const val ONLINE = "ONLINE"
  }
}

data class RegistryGate(
  val id: PartyId,
  val eDeliveryUrl: URI,
  val status: String,
  val eDeliveryCert: String? = null,
  val tlsCert: String? = null,
)

data class RegistryPlatform(
  val id: PartyId,
  val baseUrl: URI,
  val status: String,
  val eDeliveryCert: String? = null,
  val tlsCert: String? = null,
)
