import edelivery.Party
import edelivery.PartyId
import edelivery.PartyRegistry
import resql.ResqlClient
import java.util.concurrent.CopyOnWriteArrayList

class EDeliveryPartyRegistry(
  private val resqlClient: ResqlClient,
  pubSubClient: PubSubClient,
): PartyRegistry {
  @Volatile var parties = resqlClient.getParties()
  private val changeListeners = CopyOnWriteArrayList<(Party) -> Unit>()

  init {
    pubSubClient.subscribe("gate-changes", ::reload)
    pubSubClient.subscribe("platform-changes", ::reload)
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
