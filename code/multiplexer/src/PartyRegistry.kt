import klite.sleep
import resql.ResqlClient
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

class PartyRegistry(resqlClient: ResqlClient) {
  var parties = resqlClient.getGates().associateBy { it.id }
  init {
    thread {
      while (!Thread.currentThread().isInterrupted) {
        sleep(30.minutes)
        parties = resqlClient.getGates().associateBy { it.id }
      }
    }
  }
}