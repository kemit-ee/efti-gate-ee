import efti.*
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
  Server(requestIdGenerator = RequestIdHandler()).apply {
    context("/health") {
      get { "OK" }
    }

    use<JsonBody>()
    metrics()

    context("/api/v1") {
      annotated<UploadRoutes>("/upload")
      annotated<SearchRoutes>("/search")
      annotated<DatasetRoutes>("/dataset")
      annotated<FollowupRoutes>("/followup")

      openApi(annotations = listOf(
        OpenAPIDefinition(info = Info(title = "XML-Mapper", version = "1.0")),
      ))
    }

    start()
  }
}
