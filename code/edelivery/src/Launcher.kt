import edelivery.*
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import klite.*
import klite.annotations.annotated
import klite.http.httpClient
import klite.json.JsonBody
import klite.openapi.openApi
import java.util.concurrent.TimeoutException

fun main() {
  Config.useEnvFile()
  if (Config.optional("PORT") == null) Config["PORT"] = "8081"

  Server(requestIdGenerator = RequestIdHandler()).apply {
    use<JsonBody>()
    register(httpClient())
    register<PartyRegistry>(EDeliveryPartyRegistry::class)
    register<AsyncResponseProvider>(SingleNodeAsyncResponseProvider::class)
    register<MessageHandler>(EftiMessageHandler::class)

    errors.on<TimeoutException>(StatusCode.GatewayTimeout)

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
