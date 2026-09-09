import edelivery.partyId
import klite.Config
import resql.ResqlClient

class MultiplexerGateRegistry(private val resqlClient: ResqlClient) {
  val gates get() = loadOtherGates()

  private fun loadOtherGates() = resqlClient.getGates("ONLINE") - Config.partyId
}
