package efti

import RequestIdHandler
import efti.domain.UIL
import efti.xml.fti.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.HttpExchange
import klite.annotations.POST
import klite.json.toJsonValues
import klite.nodes.Node
import klite.uuid

@Tag(
  name = "Consignment upload",
  description = "These routes are for mapping requests and responses for consignment uploads."
)
class UploadRoutes(val requestIdHandler: RequestIdHandler) {
  @Operation(description = "Map FTI004UploadIdentifierRequest or UniqueIDSetUIL as XML to a flat consignment json suitable for DB insertion.")
  @POST("/request-to-json") fun requestToJson(xml: String, e: HttpExchange): Node {
    val content = if (xml.contains("FTI004UploadIdentifierRequest")) {
      val req = xmlParser.parse<FTI004UploadIdentifierRequest>(xml)
      requestIdHandler.send(e, req.document.queryId)
      req.content
    } else xmlParser.parse<UniqueIDSetUIL>(xml)
    return (content.uil.toJsonValues() + content.criteria.toJsonValues()).mapKeys { it.key.name } + ("xml" to xml)
  }

  @Operation(description = "Map UIL as JSON to FTI029UploadIdentifierResponse as XML.")
  @POST("/response-to-xml") fun responseToXml(uil: UIL, e: HttpExchange): String =
    FTI029UploadIdentifierResponse(ExchangedDocument("029", e.requestId.uuid), uil).render()
}
