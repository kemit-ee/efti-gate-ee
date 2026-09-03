package efti

import RequestIdHandler
import efti.domain.ConsignmentRow
import efti.domain.GateId
import efti.domain.PlatformId
import efti.domain.UIL
import efti.xml.fti.*
import efti.xml.fti.FTIResponseCode.Completed
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.HttpExchange
import klite.annotations.POST
import klite.uuid

@Tag(
  name = "Consignment upload",
  description = "These routes are for mapping requests and responses for consignment uploads."
)
class UploadRoutes(val requestIdHandler: RequestIdHandler) {
  @Operation(description = "Map FTI004UploadIdentifierRequest or ParameterIDSetCriteria as XML to a flat consignment json suitable for DB insertion.")
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

  @Operation(description = "Map UIL as JSON to FTI029UploadIdentifierResponse as XML.")
  @POST("/response-to-xml") fun responseToXml(uil: UIL, e: HttpExchange): String =
    FTI029UploadIdentifierResponse(ExchangedDocument("029", e.requestId.uuid, responseCode = Completed), uil).render()
}
