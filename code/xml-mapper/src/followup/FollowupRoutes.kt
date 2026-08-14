package followup

import efti.domain.UIL
import efti.xml.fti.BinaryFile
import efti.xml.fti.ExchangedDocument
import efti.xml.fti.FTI025LodgeFollowUpCommRequest
import efti.xml.fti.FTI030LodgeFollowUpCommResponse
import efti.xml.fti.FtiCapitalize
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.POST
import klite.xml.XmlParser

@Tag(
  name = "Follow-up request",
  description = "These routes are for mapping requests and responses for follow-up request."
)
class FollowupRoutes {
  val xmlParser = XmlParser(keys = FtiCapitalize)

  @Operation(description = "Map UIL and Message as JSON to FTI025LodgeFollowUpCommRequest as XML.")
  @POST("/request-to-xml") fun requestToXml(req: FollowupRequest): String =
    FTI025LodgeFollowUpCommRequest(ExchangedDocument("025", req.uil.datasetId), req.message, req.files, req.uil).render()

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
  @POST("/response-to-xml") fun responseToXml(uil: UIL): String =
    FTI030LodgeFollowUpCommResponse(ExchangedDocument("030", uil.datasetId), uil).render()
}

data class FollowupRequest(val uil: UIL, val message: String, val files: List<BinaryFile> = emptyList())
