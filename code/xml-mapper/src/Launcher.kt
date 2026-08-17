import efti.*
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import klite.*
import klite.annotations.annotated
import klite.json.JsonBody
import klite.openapi.openApi

fun main() {
  Config.useEnvFile()
  Server(requestIdGenerator = RequestIdHandler()).apply {
    context("/health") {
      get { "OK" }
    }

    useOnly<JsonOrXmlBody>()
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

class JsonOrXmlBody: JsonBody() {
  override fun render(e: HttpExchange, code: StatusCode, value: Any?) {
    if (value is String) e.send(code, value, MimeTypes.xml)
    else super.render(e, code, value)
  }
}

