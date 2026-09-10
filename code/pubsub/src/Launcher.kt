import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import klite.Config
import klite.Server
import klite.annotations.annotated
import klite.json.JsonBody
import klite.openapi.openApi
import klite.register
import pubsub.PubSubRoutes
import pubsub.TopicRegistry

fun main() {
  Config.useEnvFile()
  if (Config.optional("PORT") == null) Config["PORT"] = "8084"

  Server().apply {
    use<JsonBody>()
    register<TopicRegistry>(TopicRegistry())

    context("/health") {
      get { "OK" }
    }

    context("/api/v1") {
      annotated<PubSubRoutes>()

      openApi(annotations = listOf(
        OpenAPIDefinition(info = Info(
          title = "Pub/Sub Service",
          version = "1.0",
          description = "Simple in-memory pub/sub with SSE delivery."
        )),
      ))
    }

    start()
  }
}
