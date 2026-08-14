package search

import efti.xml.fti.ExchangedDocument
import efti.xml.fti.FTI019SearchIdentifierRequest
import efti.xml.fti.FTI021SearchIdentifierResponse
import efti.xml.fti.FtiCapitalize
import efti.xml.fti.ParameterSearchCriteria
import efti.xml.fti.UniqueIDSetUIL
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.POST
import klite.xml.XmlParser
import java.util.UUID

@Tag(
  name = "Identifiers search",
  description = "These routes are for mapping requests and responses for identifiers search."
)
class SearchRoutes {
  val xmlParser = XmlParser(keys = FtiCapitalize)

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

  @Operation(description = "Map ParameterSearchCriteria as JSON to FTI021SearchIdentifierResponse as XML. Meant for other Gate request.")
  @POST("/response-to-xml") fun responseToXml(criteria: ParameterSearchCriteria): String {
    TODO("requires UniqueIDSetUIL results, not just criteria")
  }
}
