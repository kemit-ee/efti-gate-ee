import klite.sleep
import resql.ResqlClient
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

class GateRegistry(resqlClient: ResqlClient) {
  var gates = resqlClient.getGates()
  init {
    thread(name = javaClass.simpleName, isDaemon = true) {
      while (!Thread.currentThread().isInterrupted) {
        sleep(30.minutes)
        gates = resqlClient.getGates()
      }
    }
  }
}
