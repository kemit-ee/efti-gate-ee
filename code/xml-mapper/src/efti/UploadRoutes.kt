package efti

import RequestIdHandler
import efti.domain.ConsignmentRow
import efti.domain.UIL
import efti.xml.RuuterXmlWrapper
import efti.xml.fti.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.HttpExchange
import klite.annotations.POST
import klite.uuid

@Tag(
  name = "Consignment upload",
  description = "These routes are for mapping requests and responses for consignment uploads."
)
class UploadRoutes(val requestIdHandler: RequestIdHandler) {
  @Operation(description = "Map FTI004UploadIdentifierRequest or UniqueIDSetUIL as XML to a flat consignment json suitable for DB insertion.")
  @POST("/request-to-json") fun requestToJson(xml: String, e: HttpExchange): ConsignmentRow {
    val content = if (xml.contains("FTI004UploadIdentifierRequest")) {
      val req = xmlParser.parse<FTI004UploadIdentifierRequest>(xml)
      requestIdHandler.send(e, req.document.queryId)
      req.content
    } else xmlParser.parse<UniqueIDSetUIL>(xml)
    return ConsignmentRow(content.uil.datasetId, content.uil.platformId, content.uil.gateId,
      xml.extractParameterIDSetCriteria(),
      content.criteria.transportMode,
      content.criteria.acceptanceDate?.instant,
      content.criteria.acceptanceCountry,
      content.criteria.deliveryDate?.instant,
      content.criteria.deliveryCountry,
      content.criteria.dangerousGoods,
      content.criteria.mainTransportId,
      content.criteria.mainTransportType,
      content.criteria.transportRegCountry,
      content.criteria.loadingDate?.instant,
      content.criteria.loadingCountry,
      content.criteria.unloadingDate?.instant,
      content.criteria.unloadingCountry,
      content.criteria.usedEquipmentIds,
      content.criteria.usedEquipmentCategories,
      content.criteria.usedEquipmentCountries,
      content.criteria.usedEquipmentSeq,
      content.criteria.carriedEquipmentIds,
      content.criteria.carriedEquipmentCategories,
      content.criteria.carriedEquipmentSeq,
    )
  }

  @Operation(description = "Map UIL as JSON to FTI029UploadIdentifierResponse as XML.")
  @POST("/response-to-xml") fun responseToXml(uil: UIL, e: HttpExchange): RuuterXmlWrapper =
    RuuterXmlWrapper(FTI029UploadIdentifierResponse(ExchangedDocument("029", e.requestId.uuid), uil).render())
}
