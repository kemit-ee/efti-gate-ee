import klite.Config
import klite.Server
import klite.metrics

fun main() {
  Config.useEnvFile()
  val fromPartyId = Config["OWN_PARTY_ID"]
  // TODO: val partyRegistry load on startup using ReSql (CI/whatever will restart us on change)

  Server().apply {
    metrics()

    context("/health") {
      get { "OK" }
    }

    // Internal
    context("/api/v1") {
      post("/send/:partyId") {
        // body as xml payload
      }
    }

    // Internet-facing
    context("/services/msh") {
      post {
        // body as xml payload
      }
    }

    start()
  }
}

/** Forwards raw EFTI xmls to Ruuter for further conversion */
interface RuuterClient {
  fun saveConsignment(xml: String /* FTI004UploadIdentifierRequest */)
  fun searchConsignments(xml: String /* FTI019SearchIdentifierRequest */)
  fun getDataset(xml: String /* FTI009GetCmdsRequest */)
  fun followUp(xml: String /* FTI025LodgeFollowUpCommRequest */)
}
