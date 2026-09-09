package efti

import RequestIdHandler
import efti.domain.ConsignmentRow
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
import klite.StatusCode.Companion.NotFound
import klite.annotations.POST
import klite.uuid

@Tag(
  name = "Identifiers search",
  description = "These routes are for mapping requests and responses for identifiers search."
)
class SearchRoutes(val requestIdHandler: RequestIdHandler) {
  @Operation(summary = "Create an identifier search request", description = "Maps search criteria JSON to an FTI019SearchIdentifierRequest XML document.")
  @ApiResponse(responseCode = "200", description = "FTI019SearchIdentifierRequest XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/request-to-xml") fun requestToXml(criteria: ParameterSearchCriteria, e: HttpExchange): String =
    FTI019SearchIdentifierRequest(ExchangedDocument("019", e.requestId.uuid), criteria).render()

  @Operation(summary = "Parse an identifier search request", description = "Maps an FTI019SearchIdentifierRequest XML document to search criteria JSON.")
  @RequestBody(description = "FTI019SearchIdentifierRequest XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/request-to-json") fun requestToJson(xml: String, e: HttpExchange): ParameterSearchCriteria {
    val req = xmlParser.parse<FTI019SearchIdentifierRequest>(xml)
    requestIdHandler.send(e, req.document.queryId)
    return req.searchCriteria
  }

  @Operation(summary = "Parse multiplexed search responses", description = "Maps one or more FTI021SearchIdentifierResponse XML documents, separated by ⦀, to ConsignmentRow JSON. Used for authority requests after multiplexer fan-out.")
  @RequestBody(description = "One or more FTI021SearchIdentifierResponse XML documents separated by ⦀", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/response-to-json") fun responseToJson(xml: String): List<ConsignmentRow> =
    xml.split("⦀").filter { it.isNotEmpty() }
      .flatMap { xml -> xmlParser.parse<FTI021SearchIdentifierResponse>(xml).content?.map {
        r -> ConsignmentRow(r.uil, r.criteria!!, xml.extractParameterIDSetCriteria())
      } ?: emptyList() }

  @Operation(summary = "Create an identifier search response", description = "Maps consignment rows to an FTI021SearchIdentifierResponse XML document for a gate-to-gate request.")
  @ApiResponse(responseCode = "200", description = "FTI021SearchIdentifierResponse XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/response-to-xml") fun responseToXml(consignments: List<ConsignmentRow>, e: HttpExchange): String {
    val note = if (consignments.isEmpty()) IncludedNote("No consignments found", NotFound) else null
    return FTI021SearchIdentifierResponse(ExchangedDocument("021", e.requestId.uuid, responseCode = Completed, includedNote = note)).render(
      consignments.map { UniqueIDSetUniqueIDSet(UIL(it.platformId, it.datasetId, it.gateId)).render(it.xml) }
    )
  }
}
