import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import klite.Config
import klite.Server
import klite.StatusCode.Companion.GatewayTimeout
import klite.annotations.annotated
import klite.http.httpClient
import klite.json.JsonBody
import klite.openapi.openApi
import klite.register

fun main() {
  Config.useEnvFile()
  if (Config.optional("PORT") == null) Config["PORT"] = "8083"

  Server().apply {
    use<JsonBody>()
    register(httpClient())

    errors.on<InterruptedException>(GatewayTimeout)

    context("/health") {
      get { "OK" }
    }

    context("/api/v1") {
      annotated<MultiplexerRoutes>()

      openApi(annotations = listOf(
        OpenAPIDefinition(info = Info(title = "Multiplexer", version = "1.0")),
      ))
    }

    start()
  }
}
