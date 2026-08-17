import edelivery.EDeliveryParty
import edelivery.Party
import edelivery.PartyId
import edelivery.PartyRegistry
import java.util.concurrent.ConcurrentHashMap

class EDeliveryPartyRegistry: PartyRegistry {
  private val parties = ConcurrentHashMap<PartyId, EDeliveryParty>()

  override operator fun get(id: PartyId): Party = parties[id] ?: error("Unknown party: $id")
  override fun onChange(listener: (Party) -> Unit) {}
  override fun list(): List<Party> = parties.values.toList()
}
