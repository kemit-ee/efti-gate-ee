import edelivery.EDeliveryMetrics
import edelivery.Party
import edelivery.PartyId
import edelivery.PartyRegistry
import klite.Metrics
import klite.info
import klite.logger
import registry.RegistryClient
import java.util.concurrent.CopyOnWriteArrayList

class EDeliveryPartyRegistry(
  private val registryClient: RegistryClient,
  refreshInterval: kotlin.time.Duration = registryRefreshInterval,
): PartyRegistry {
  private val log = logger()
  @Volatile var parties = registryClient.getParties()
  @Volatile var generation: String? = null
    private set
  private val changeListeners = CopyOnWriteArrayList<(Party) -> Unit>()

  init {
    refreshGeneration()
    refreshPeriodically("party-registry-refresh", refreshInterval) { reload() }
    // ONLINE gates/platforms known to this node right now (RegistryClient.getParties() already
    // excludes DISABLED entries) -- the "gate ONLINE/OFFLINE" signal from the monitoring architecture.
    Metrics.register("edeliveryPartiesOnlineTotal") { parties.size }
  }

  fun reload() {
    parties = registryClient.getParties()
    refreshGeneration()
    // notify for every current party rather than diffing: listeners only evict/rebuild caches, so
    // over-notifying on an infrequent registry change is cheap and can't miss a rotated cert.
    parties.values.forEach { party -> changeListeners.forEach { it(party) } }
  }

  // A failed read keeps the last known generation: the parties just loaded are the ones that count, and the gauge must not flap to 0.
  private fun refreshGeneration() {
    val current = registryClient.getGeneration() ?: return
    if (current != generation) log.info("Registry generation $current (was ${generation ?: "unknown"})")
    generation = current
    EDeliveryMetrics.registryGeneration(current)
  }

  override operator fun get(id: PartyId): Party = parties[id] ?: error("Unknown party: $id")
  override fun onChange(listener: (Party) -> Unit) { changeListeners.add(listener) }
  override fun list(): List<Party> = parties.values.toList()
}
