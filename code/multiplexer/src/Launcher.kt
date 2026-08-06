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
  // TODO: val gateRegistry load on startup using ReSql (CI/whatever will restart us on change)
  Server().apply {
    use<JsonBody>()
    metrics()

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
