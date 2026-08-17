import efti.DatasetRoutes
import efti.FollowupRoutes
import efti.SearchRoutes
import efti.UploadRoutes
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import klite.*
import klite.annotations.annotated
import klite.json.JsonBody
import klite.openapi.openApi
import java.util.*

fun main() {
  Config.useEnvFile()
  Server(requestIdGenerator = object: RequestIdGenerator() {
    override fun invoke(headers: Headers) = headers.getFirst("x-request-id") ?: UUID.randomUUID().toString()
  }).apply {
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
