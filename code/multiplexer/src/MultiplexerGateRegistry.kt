import edelivery.EDeliveryParty
import edelivery.PartyId
import edelivery.partyId
import klite.*
import klite.http.timeout
import klite.sse.getSSE
import resql.ResqlClient
import java.net.URI
import java.net.http.HttpClient
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

const val gateChangeEvent = "gate-changes"

class MultiplexerGateRegistry(
  private val resqlClient: ResqlClient,
  private val http: HttpClient,
) {
  private val log = logger()
  private val ownGateId: PartyId = Config.partyId
  private val pubsubUrl: URI = URI(Config["PUBSUB_URL"])
  @Volatile private var gateCache: Map<PartyId, EDeliveryParty> = loadGates()

  val gates: Map<PartyId, EDeliveryParty> get() = gateCache

  init {
    thread(name = "gate-changes-sse", isDaemon = true) {
      while (true) {
        try { subscribeToGateChanges() } catch (e: Exception) {
          log.warn("SSE connection error, reconnecting: ${e.message}")
          sleep(1.seconds)
        }
      }
    }
  }

  private fun loadGates() = resqlClient.getGates("ONLINE") - ownGateId

  private fun subscribeToGateChanges() {
    log.info("Subscribing to pubsub SSE stream for gate changes")
    http.getSSE(pubsubUrl.resolve("/api/v1/subscribe/$gateChangeEvent")) { timeout(1.hours) }.forEach { event ->
      log.info("Gate change event received, refetching gates")
      gateCache = loadGates()
    }
  }
}
