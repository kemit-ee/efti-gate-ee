import edelivery.EDeliveryParty
import edelivery.Party
import edelivery.PartyId
import edelivery.PartyRegistry
import klite.info
import klite.logger
import java.util.concurrent.ConcurrentHashMap

class EDeliveryPartyRegistry: PartyRegistry {
  private val parties = ConcurrentHashMap<PartyId, EDeliveryParty>()

  private val log = logger()

  override operator fun get(id: PartyId): Party = parties[id] ?: error("Unknown party: $id")
  override fun onChange(listener: (Party) -> Unit) {}
  override fun list(): List<Party> = parties.values.toList()

  fun load(p: Map<PartyId, EDeliveryParty>) {
    p.forEach { log.info("eDelivery party ${it.key} initialized") }
    parties.putAll(p)
  }
}
