package efti

import RequestIdHandler
import efti.domain.ConsignmentRow
import efti.xml.fti.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.HttpExchange
import klite.annotations.POST
import klite.uuid

@Tag(
  name = "Identifiers search",
  description = "These routes are for mapping requests and responses for identifiers search."
)
class SearchRoutes(val requestIdHandler: RequestIdHandler) {
  @Operation(description = "Map ParameterSearchCriteria as JSON to Fti019SearchIdentifierRequest as XML.")
  @POST("/request-to-xml") fun requestToXml(criteria: ParameterSearchCriteria, e: HttpExchange): String =
    FTI019SearchIdentifierRequest(ExchangedDocument("019", e.requestId.uuid), criteria).render()

  @Operation(description = "Map FTI019SearchIdentifierRequest as XML to ParameterSearchCriteria as JSON.")
  @POST("/request-to-json") fun requestToJson(xml: String, e: HttpExchange): ParameterSearchCriteria {
    val req = xmlParser.parse<FTI019SearchIdentifierRequest>(xml)
    requestIdHandler.send(e, req.document.queryId)
    return req.searchCriteria
  }

  @Operation(description = "Map one or more FTI021SearchIdentifierResponse as XML with delimiter to multiple ConsignmentRow as JSON. Coming from multiplexer, meant for Authority request.")
  @POST("/response-to-json") fun responseToJson(xml: String): List<ConsignmentRow> =
    xml.split("⦀").flatMap { xml -> xmlParser.parse<FTI021SearchIdentifierResponse>(xml).content?.map { r -> ConsignmentRow(r, xml) } ?: emptyList() }

  @Operation(description = "Map ConsignmentRow as JSON to FTI021SearchIdentifierResponse as XML. Meant for other Gate request.")
  @POST("/response-to-xml") fun responseToXml(consignments: List<ConsignmentRow>, e: HttpExchange): String =
    FTI021SearchIdentifierResponse(ExchangedDocument("021", e.requestId.uuid)).render(consignments.map { it.xml })
}
