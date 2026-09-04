import edelivery.partyId
import klite.Config
import klite.sleep
import resql.ResqlClient
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

class MultiplexerGateRegistry(private val resqlClient: ResqlClient) {
  var gates = loadOtherGates()
  init {
    thread(name = javaClass.simpleName, isDaemon = true) {
      while (!Thread.currentThread().isInterrupted) {
        sleep(5.minutes)
        gates = loadOtherGates()
      }
    }
  }

  private fun loadOtherGates() = resqlClient.getGates("ONLINE") - Config.partyId
}
