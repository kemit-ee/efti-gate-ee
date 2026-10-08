import edelivery.Party
import edelivery.PartyId
import edelivery.PartyRegistry
import registry.RegistryClient
import java.util.concurrent.CopyOnWriteArrayList

class EDeliveryPartyRegistry(
  private val registryClient: RegistryClient,
  refreshInterval: kotlin.time.Duration = registryRefreshInterval,
): PartyRegistry {
  @Volatile var parties = registryClient.getParties()
  private val changeListeners = CopyOnWriteArrayList<(Party) -> Unit>()

  init {
    refreshPeriodically("party-registry-refresh", refreshInterval) { reload() }
  }

  fun reload() {
    parties = registryClient.getParties()
    // notify for every current party rather than diffing: listeners only evict/rebuild caches, so
    // over-notifying on an infrequent registry change is cheap and can't miss a rotated cert.
    parties.values.forEach { party -> changeListeners.forEach { it(party) } }
  }

  override operator fun get(id: PartyId): Party = parties[id] ?: error("Unknown party: $id")
  override fun onChange(listener: (Party) -> Unit) { changeListeners.add(listener) }
  override fun list(): List<Party> = parties.values.toList()
}
