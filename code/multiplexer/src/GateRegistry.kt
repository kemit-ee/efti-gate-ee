import edelivery.partyId
import klite.Config
import klite.sleep
import resql.ResqlClient
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

class GateRegistry(private val resqlClient: ResqlClient) {
  var gates = loadOtherGates()
  init {
    thread(name = javaClass.simpleName, isDaemon = true) {
      while (!Thread.currentThread().isInterrupted) {
        sleep(30.minutes)
        gates = loadOtherGates()
      }
    }
  }

  private fun loadOtherGates() = resqlClient.getGates() - Config.partyId
}
