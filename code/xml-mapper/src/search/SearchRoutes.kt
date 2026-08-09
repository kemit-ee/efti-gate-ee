package search

import domain.ParameterSearchCriteria
import domain.UniqueIDSetUIL
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.POST

@Tag(
  name = "Identifiers search",
  description = "These routes are for mapping requests and responses for identifiers search."
)
class SearchRoutes {
  @Operation(description = "Map ParameterSearchCriteria as JSON to Fti019SearchIdentifierRequest as XML.")
  @POST("/request-to-xml") fun requestToXml(criteria: ParameterSearchCriteria): String {
    TODO("Implement")
  }

  @Operation(description = "Map FTI019SearchIdentifierRequest as XML to ParameterSearchCriteria as JSON.")
  @POST("/request-to-json") fun requestToJson(xml: String): ParameterSearchCriteria {
    TODO("Implement")
  }

  @Operation(description = "Map one or more FTI021SearchIdentifierResponse as XML with delimiter to multiple UniqueIDSetUIL as JSON. Meant for Authority request.")
  @POST("/response-to-json") fun responseToJson(xml: String): List<UniqueIDSetUIL> {
    TODO("Implement")
  }

  @Operation(description = "Map ParameterSearchCriteria as JSON to FTI021SearchIdentifierResponse as XML. Meant for other Gate request.")
  @POST("/response-to-xml") fun responseToXml(criteria: ParameterSearchCriteria): String {
    TODO("Implement")
  }
}
