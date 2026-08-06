import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import klite.Config
import klite.Server
import klite.annotations.annotated
import klite.http.httpClient
import klite.json.JsonBody
import klite.metrics
import klite.openapi.openApi
import klite.register

fun main() {
  Config.useEnvFile()
//  val fromPartyId = Config["OWN_PARTY_ID"]
  // TODO: val partyRegistry load on startup using ReSql (CI/whatever will restart us on change)

  Server().apply {
    use<JsonBody>()
    register(httpClient())
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