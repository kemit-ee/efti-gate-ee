import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import klite.Config
import klite.Server
import klite.HttpExchange
import klite.MimeTypes
import klite.StatusCode
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
    useOnly<MultiplexerBody>()
    register(httpClient())

    errors.on<InterruptedException>(GatewayTimeout)

    context("/health") {
      get { "OK" }
    }

    context("/api/v1") {
      annotated<MultiplexerRoutes>()

      openApi(annotations = listOf(
        OpenAPIDefinition(info = Info(
          title = "Multiplexer",
          version = "1.0",
          description = "Internal fan-out API for querying registered eFTI gates."
        )),
      ))
    }

    start()
  }
}

class MultiplexerBody: JsonBody() {
  override fun render(e: HttpExchange, code: StatusCode, value: Any?) {
    if (value is String) e.send(code, value, MimeTypes.xml)
    else super.render(e, code, value)
  }
}
