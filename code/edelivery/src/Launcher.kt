import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import klite.Config
import klite.Server
import klite.annotations.annotated
import klite.json.JsonBody
import klite.metrics
import klite.openapi.openApi

fun main() {
  Config.useEnvFile()
//  val fromPartyId = Config["OWN_PARTY_ID"]
  // TODO: val partyRegistry load on startup using ReSql (CI/whatever will restart us on change)

  Server().apply {
    use<JsonBody>()
    metrics()

    context("/health") {
      get { "OK" }
    }

    // Internal
    context("/api/v1") {
      annotated<InternalRoutes>()

      openApi(annotations = listOf(
        OpenAPIDefinition(info = Info(title = "eDelivery", version = "1.0")),
      ))
    }

    // Internet-facing
    context("/services") {
      annotated<EDeliveryRoutes>()
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
