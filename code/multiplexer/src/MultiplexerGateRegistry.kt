import edelivery.EDeliveryParty
import edelivery.PartyId
import edelivery.partyId
import klite.*
import resql.ResqlClient
import kotlin.time.Duration

class MultiplexerGateRegistry(
  private val resqlClient: ResqlClient,
  refreshInterval: Duration = registryRefreshInterval,
) {
  private val ownGateId: PartyId = Config.partyId
  @Volatile private var gateCache: Map<PartyId, EDeliveryParty> = loadGates()

  val gates: Map<PartyId, EDeliveryParty> get() = gateCache

  init {
    refreshPeriodically("gate-registry-refresh", refreshInterval) { gateCache = loadGates() }
  }

  private fun loadGates() = resqlClient.getGates("ONLINE") - ownGateId
}
