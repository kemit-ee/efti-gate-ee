package efti

import RequestIdHandler
import efti.domain.ConsignmentRow
import efti.domain.GateId
import efti.domain.PlatformId
import efti.domain.UIL
import efti.xml.fti.*
import efti.xml.fti.FTIResponseCode.Completed
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import klite.HttpExchange
import klite.MimeTypes
import klite.annotations.POST
import klite.uuid

@Tag(
  name = "Consignment upload",
  description = "These routes are for mapping requests and responses for consignment uploads."
)
class UploadRoutes(val requestIdHandler: RequestIdHandler) {
  @Operation(summary = "Map an upload request to a consignment", description = "Maps an FTI004UploadIdentifierRequest or ParameterIDSetCriteria XML document to the flat ConsignmentRow JSON used for database insertion.")
  @RequestBody(description = "FTI004UploadIdentifierRequest or ParameterIDSetCriteria XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/request-to-json") fun requestToJson(xml: String, e: HttpExchange): ConsignmentRow {
    val isFullRequest = xml.contains("FTI004UploadIdentifierRequest")
    val (uil, criteria) = if (isFullRequest) {
      val req = xmlParser.parse<FTI004UploadIdentifierRequest>(xml)
      requestIdHandler.send(e, req.document.queryId)
      req.content.uil to req.content.criteria!!
    } else {
      UIL(PlatformId(e.header("X-Platform-Id")!!), e.header("X-Dataset-Id")!!.uuid, GateId(e.header("X-Gate-Id")!!)) to
      xmlParser.parse<ParameterIDSetCriteria>(xml)
    }
    val criteriaXml = if (isFullRequest) xml.extractParameterIDSetCriteria() else xml
    return ConsignmentRow(uil, criteria, criteriaXml)
  }

  @Operation(summary = "Create an upload response", description = "Maps a platform or gate identifier (UIL) to an FTI029UploadIdentifierResponse XML document.")
  @ApiResponse(responseCode = "200", description = "FTI029UploadIdentifierResponse XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/response-to-xml") fun responseToXml(uil: UIL, e: HttpExchange): String =
    FTI029UploadIdentifierResponse(ExchangedDocument("029", e.requestId.uuid, responseCode = Completed), uil).render()
}
