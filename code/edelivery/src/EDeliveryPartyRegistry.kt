import edelivery.Party
import edelivery.PartyId
import edelivery.PartyRegistry
import klite.*
import klite.http.timeout
import klite.sse.getSSE
import resql.ResqlClient
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class EDeliveryPartyRegistry(
  private val resqlClient: ResqlClient,
  private val http: HttpClient,
  private val internalServiceToken: String = Config.optional("INTERNAL_SERVICE_TOKEN").orEmpty(),
): PartyRegistry {
  private val log = logger()
  private val pubsubUrl: URI = URI(Config["PUBSUB_URL"])
  @Volatile var parties = resqlClient.getParties()
  private val changeListeners = CopyOnWriteArrayList<(Party) -> Unit>()

  init {
    subscribeSSE("gate-changes")
    subscribeSSE("platform-changes")
  }

  private fun subscribeSSE(topic: String) {
    thread(name = "$topic-sse", isDaemon = true) {
      while (true) {
        try {
          log.info("Subscribing to pubsub SSE stream for $topic")
          // reload before (re)subscribing: catches up on any change missed while disconnected
          reload()
          http.getSSE(pubsubUrl.resolve("/api/v1/subscribe/$topic")) {
            timeout(1.hours)
            header("X-Internal-Service-Token", internalServiceToken)
          }.forEach {
            log.info("$topic event received, refetching parties")
            reload()
          }
        } catch (e: Exception) {
          log.warn("SSE connection error for $topic, reconnecting: ${e.message}")
          sleep(1.seconds)
        }
      }
    }
  }

  fun reload() {
    parties = resqlClient.getParties()
    // notify for every current party rather than diffing: listeners only evict/rebuild caches, so
    // over-notifying on an infrequent registry change is cheap and can't miss a rotated cert.
    parties.values.forEach { party -> changeListeners.forEach { it(party) } }
  }

  override operator fun get(id: PartyId): Party = parties[id] ?: error("Unknown party: $id")
  override fun onChange(listener: (Party) -> Unit) { changeListeners.add(listener) }
  override fun list(): List<Party> = parties.values.toList()
}
