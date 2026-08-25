package efti

import RequestIdHandler
import efti.domain.UIL
import efti.xml.fti.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.HttpExchange
import klite.annotations.POST
import klite.uuid
import java.util.*

@Tag(
  name = "Follow-up request",
  description = "These routes are for mapping requests and responses for follow-up request."
)
class FollowupRoutes(val requestIdHandler: RequestIdHandler) {
  @Operation(description = "Map UIL and Message as JSON to FTI025LodgeFollowUpCommRequest as XML.")
  @POST("/request-to-xml") fun requestToXml(req: FollowupRequest, e: HttpExchange): String =
    FTI025LodgeFollowUpCommRequest(ExchangedDocument("025", e.requestId.uuid, referencedIds = req.referenceIds), FollowUp(req.message, req.files), req.uil).render()

  @Operation(description = "Map FTI025LodgeFollowUpCommRequest as XML to UIL and Message as JSON.")
  @POST("/request-to-json") fun requestToJson(xml: String, e: HttpExchange): FollowupRequest {
    val req = xmlParser.parse<FTI025LodgeFollowUpCommRequest>(xml)
    requestIdHandler.send(e, req.document.queryId)
    return FollowupRequest(req.uil, req.document.referencedIds ?: emptyList(), req.followUp.message ?: "", req.followUp.files)
  }

  @Operation(description = "Map FTI030LodgeFollowUpCommResponse as XML to UIL as JSON.")
  @POST("/response-to-json") fun responseToJson(xml: String, e: HttpExchange): UIL {
    val resp = xmlParser.parse<FTI030LodgeFollowUpCommResponse>(xml)
    requestIdHandler.send(e, resp.document.queryId)
    return resp.uil
  }

  @Operation(description = "Map UIL as JSON to FTI030LodgeFollowUpCommResponse as XML.")
  @POST("/response-to-xml") fun responseToXml(uil: UIL, e: HttpExchange): String =
    FTI030LodgeFollowUpCommResponse(ExchangedDocument("030", e.requestId.uuid), uil).render()
}

data class FollowupRequest(val uil: UIL, val referenceIds: List<UUID>, val message: String, val files: List<BinaryFile> = emptyList())
