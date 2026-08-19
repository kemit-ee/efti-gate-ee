package efti

import RequestIdHandler
import efti.xml.RuuterXmlWrapper
import efti.xml.fti.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.HttpExchange
import klite.annotations.POST
import klite.uuid
import java.time.Instant

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

  @Operation(description = "Map one or more FTI021SearchIdentifierResponse as XML with delimiter to multiple UniqueIDSetUIL as JSON. Coming from multiplexer, meant for Authority request.")
  @POST("/response-to-json") fun responseToJson(xml: String): List<UniqueIDSetUIL> {
    val regex = "<([^:>]+:|)FTI021SearchIdentifierResponse(?:\\s[^>]*)?>.*?</\\1FTI021SearchIdentifierResponse>".toRegex(RegexOption.DOT_MATCHES_ALL)
    return regex.findAll(xml).flatMap { xmlParser.parse<FTI021SearchIdentifierResponse>(it.value).content }.toList()
  }

  @Operation(description = "Map UniqueIDSetUIL as JSON to FTI021SearchIdentifierResponse as XML. Meant for other Gate request.")
  @POST("/response-to-xml") fun responseToXml(consignments: List<UniqueIDSetUIL>, e: HttpExchange): RuuterXmlWrapper =
    // TODO: use List<ConsignmentRow> as input and map it to List<UniqueIDSetUIL>
    RuuterXmlWrapper(FTI021SearchIdentifierResponse(ExchangedDocument("021", e.requestId.uuid), consignments).render())
}

data class ConsignmentRow(
  val datasetId: String,
  val platformId: String,
  val gateId: String,
  val xml: String,
  val status: String,
  val transportMode: String?,
  val acceptanceDate: Instant?,
  val acceptanceCountry: String?,
  val deliveryDate: Instant?,
  val deliveryCountry: String?,
  val dangerousGoods: String?,
  val mainTransportId: String?,
  val mainTransportType: String?,
  val transportRegCountry: String?,
  val loadingDate: Instant?,
  val loadingCountry: String?,
  val unloadingDate: Instant?,
  val unloadingCountry: String?,
  val usedEquipmentIds: List<String>?,
  val usedEquipmentCategories: List<String>?,
  val usedEquipmentCountries: List<String>?,
  val usedEquipmentSeq: List<String>?,
  val carriedEquipmentIds: List<String>?,
  val carriedEquipmentCategories: List<String>?,
  val carriedEquipmentSeq: List<String>?,
)