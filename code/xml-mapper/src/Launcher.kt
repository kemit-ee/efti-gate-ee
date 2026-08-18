import efti.DatasetRoutes
import efti.FollowupRoutes
import efti.SearchRoutes
import efti.UploadRoutes
import efti.xml.fti.DateTimeString
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import klite.*
import klite.annotations.annotated
import klite.json.JsonBody
import klite.json.JsonMapper
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

val jsonMapper = JsonMapper(renderNulls = true, values = object: ValueConverter<Any?>() {
  override fun to(o: Any?) =
    if (o is DateTimeString) o.instant.toString()
    else super.to(o)
})

class JsonOrXmlBody: JsonBody(jsonMapper) {
  override fun render(e: HttpExchange, code: StatusCode, value: Any?) {
    if (value is String) e.send(code, value, MimeTypes.xml)
    else super.render(e, code, value)
  }
}
