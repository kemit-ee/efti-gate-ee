package efti

import RequestIdHandler
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
import java.util.*

@Tag(
  name = "Follow-up request",
  description = "These routes are for mapping requests and responses for follow-up request."
)
class FollowUpRoutes(val requestIdHandler: RequestIdHandler) {
  @Operation(summary = "Create a follow-up request", description = "Maps a UIL, referenced message identifiers, message text, and files to an FTI025LodgeFollowUpCommRequest XML document.")
  @ApiResponse(responseCode = "200", description = "FTI025LodgeFollowUpCommRequest XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/request-to-xml") fun requestToXml(req: FollowUpRequest, e: HttpExchange): String =
    FTI025LodgeFollowUpCommRequest(ExchangedDocument("025", e.requestId.uuid, referencedIds = req.referenceIds), FollowUp(req.message, req.files), req.uil).render()

  @Operation(summary = "Parse a follow-up request", description = "Maps an FTI025LodgeFollowUpCommRequest XML document to its UIL, references, message, and files.")
  @RequestBody(description = "FTI025LodgeFollowUpCommRequest XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/request-to-json") fun requestToJson(xml: String, e: HttpExchange): FollowUpRequest {
    val req = xmlParser.parse<FTI025LodgeFollowUpCommRequest>(xml)
    requestIdHandler.send(e, req.document.queryId)
    return FollowUpRequest(req.uil, req.document.referencedIds ?: emptyList(), req.followUp.message ?: "", req.followUp.files)
  }

  @Operation(summary = "Parse a follow-up response", description = "Maps an FTI030LodgeFollowUpCommResponse XML document to its UIL.")
  @RequestBody(description = "FTI030LodgeFollowUpCommResponse XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/response-to-json") fun responseToJson(xml: String, e: HttpExchange): UIL {
    val resp = xmlParser.parse<FTI030LodgeFollowUpCommResponse>(xml)
    requestIdHandler.send(e, resp.document.queryId)
    return resp.uil
  }

  @Operation(summary = "Create a follow-up response", description = "Maps a UIL to an FTI030LodgeFollowUpCommResponse XML document.")
  @ApiResponse(responseCode = "200", description = "FTI030LodgeFollowUpCommResponse XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/response-to-xml") fun responseToXml(uil: UIL, e: HttpExchange): String =
    FTI030LodgeFollowUpCommResponse(ExchangedDocument("030", e.requestId.uuid, responseCode = Completed), uil).render()
}

data class FollowUpRequest(val uil: UIL, val referenceIds: List<UUID> = emptyList(), val message: String, val files: List<BinaryFile> = emptyList())
