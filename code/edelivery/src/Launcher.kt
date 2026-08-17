import edelivery.AsyncResponseProvider
import edelivery.EDeliveryRoutes
import edelivery.MessageHandler
import edelivery.PartyRegistry
import edelivery.SingleNodeAsyncResponseProvider
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
import klite.require

fun main() {
  Config.useEnvFile()

  Server().apply {
    use<JsonBody>()
    register(httpClient())
    require<RuuterClient>().apply {
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