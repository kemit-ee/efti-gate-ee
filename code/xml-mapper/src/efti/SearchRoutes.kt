package efti

import efti.xml.fti.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.POST
import java.util.*

@Tag(
  name = "Identifiers search",
  description = "These routes are for mapping requests and responses for identifiers search."
)
class SearchRoutes {
  @Operation(description = "Map ParameterSearchCriteria as JSON to Fti019SearchIdentifierRequest as XML.")
  @POST("/request-to-xml") fun requestToXml(criteria: ParameterSearchCriteria): String =
    // TODO: queryId should come from somewhere?
    FTI019SearchIdentifierRequest(ExchangedDocument("019", UUID.randomUUID()), criteria).render()

  @Operation(description = "Map FTI019SearchIdentifierRequest as XML to ParameterSearchCriteria as JSON.")
  @POST("/request-to-json") fun requestToJson(xml: String): ParameterSearchCriteria {
    val req = xmlParser.parse<FTI019SearchIdentifierRequest>(xml)
    return req.searchCriteria
  }

  @Operation(description = "Map one or more FTI021SearchIdentifierResponse as XML with delimiter to multiple UniqueIDSetUIL as JSON. Coming from multiplexer, meant for Authority request.")
  @POST("/response-to-json") fun responseToJson(xml: String): List<UniqueIDSetUIL> {
    val regex = "<([^:>]+:|)FTI021SearchIdentifierResponse(?:\\s[^>]*)?>.*?</\\1FTI021SearchIdentifierResponse>".toRegex(RegexOption.DOT_MATCHES_ALL)
    return regex.findAll(xml).flatMap { xmlParser.parse<FTI021SearchIdentifierResponse>(it.value).content }.toList()
  }

  @Operation(description = "Map UniqueIDSetUIL as JSON to FTI021SearchIdentifierResponse as XML. Meant for other Gate request.")
  @POST("/response-to-xml") fun responseToXml(consignments: List<UniqueIDSetUIL>): String =
    // TODO: maybe create a more convenient class that corresponds to the consignments table as input
    // TODO: queryId should come from somewhere?
    FTI021SearchIdentifierResponse(ExchangedDocument("021", UUID.randomUUID()), consignments).render()
}
