import edelivery.*
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import klite.*
import klite.annotations.annotated
import klite.http.httpClient
import klite.json.JsonBody
import klite.openapi.openApi
import resql.ResqlClient

fun main() {
  Config.useEnvFile()

  Server(requestIdGenerator = RequestIdHandler()).apply {
    use<JsonBody>()
    register(httpClient())
    require<ResqlClient>().apply {
      val partyRegistry = require<EDeliveryPartyRegistry>()
      partyRegistry.load(getParties())
      register<PartyRegistry>(partyRegistry)
    }
    register<AsyncResponseProvider>(SingleNodeAsyncResponseProvider::class)
    register<MessageHandler>(EftiMessageHandler::class)
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