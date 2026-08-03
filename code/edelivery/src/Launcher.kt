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

interface RuuterClient {
  fun postConsignment(xml: String /* FTI004UploadIdentifierRequest */)
}
