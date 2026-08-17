import klite.sleep
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

class PartyRegistry(resqlClient: ResqlClient) {
  var parties = resqlClient.getParties()
  init {
    thread {
      while (!Thread.currentThread().isInterrupted) {
        sleep(30.minutes)
        parties = resqlClient.getParties()
      }
    }
  }
}