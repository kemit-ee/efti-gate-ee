import edelivery.Party
import edelivery.PartyId
import edelivery.PartyRegistry
import klite.sleep
import resql.ResqlClient
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

class EDeliveryPartyRegistry(private val resqlClient: ResqlClient): PartyRegistry {
  var parties = resqlClient.getParties()
  init {
    thread {
      while (!Thread.currentThread().isInterrupted) {
        sleep(30.minutes)
        reload()
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
