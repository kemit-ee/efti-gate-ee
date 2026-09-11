import edelivery.Party
import edelivery.PartyId
import edelivery.PartyRegistry
import klite.*
import klite.http.timeout
import klite.sse.getSSE
import resql.ResqlClient
import java.net.URI
import java.net.http.HttpClient
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class EDeliveryPartyRegistry(
  private val resqlClient: ResqlClient,
  private val http: HttpClient,
): PartyRegistry {
  private val log = logger()
  private val pubsubUrl: URI = URI(Config["PUBSUB_URL"])
  @Volatile var parties = resqlClient.getParties()

  init {
    subscribeSSE("gate-changes")
    subscribeSSE("platform-changes")
  }

  private fun subscribeSSE(topic: String) {
    thread(name = "$topic-sse", isDaemon = true) {
      while (true) {
        try {
          log.info("Subscribing to pubsub SSE stream for $topic")
          http.getSSE(pubsubUrl.resolve("/api/v1/subscribe/$topic")) { timeout(1.hours) }.forEach {
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
  }

  override operator fun get(id: PartyId): Party = parties[id] ?: error("Unknown party: $id")
  override fun onChange(listener: (Party) -> Unit) {}
  override fun list(): List<Party> = parties.values.toList()
}
