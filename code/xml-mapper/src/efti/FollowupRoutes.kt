package efti

import efti.domain.UIL
import efti.xml.fti.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.HeaderParam
import klite.annotations.POST
import java.util.*

@Tag(
  name = "Follow-up request",
  description = "These routes are for mapping requests and responses for follow-up request."
)
class FollowupRoutes {
  @Operation(description = "Map UIL and Message as JSON to FTI025LodgeFollowUpCommRequest as XML.")
  @POST("/request-to-xml") fun requestToXml(req: FollowupRequest, @HeaderParam("x-request-id") queryId: UUID = UUID.randomUUID()): String =
    FTI025LodgeFollowUpCommRequest(ExchangedDocument("025", queryId), req.message, req.files, req.uil).render()

  @Operation(description = "Map FTI025LodgeFollowUpCommRequest as XML to UIL and Message as JSON.")
  @POST("/request-to-json") fun requestToJson(xml: String): FollowupRequest {
    val req = xmlParser.parse<FTI025LodgeFollowUpCommRequest>(xml)
    return FollowupRequest(req.uil, req.followUp ?: "", req.files)
  }

  @Operation(description = "Map FTI030LodgeFollowUpCommResponse as XML to UIL as JSON.")
  @POST("/response-to-json") fun responseToJson(xml: String): UIL {
    val resp = xmlParser.parse<FTI030LodgeFollowUpCommResponse>(xml)
    return resp.uil
  }

  @Operation(description = "Map UIL as JSON to FTI030LodgeFollowUpCommResponse as XML.")
  @POST("/response-to-xml") fun responseToXml(uil: UIL, @HeaderParam("x-request-id") queryId: UUID = UUID.randomUUID()): String =
    FTI030LodgeFollowUpCommResponse(ExchangedDocument("030", queryId), uil).render()
}

data class FollowupRequest(val uil: UIL, val message: String, val files: List<BinaryFile> = emptyList())
