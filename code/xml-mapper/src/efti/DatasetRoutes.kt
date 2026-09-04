package efti

import RequestIdHandler
import efti.domain.UIL
import efti.subsets.Subset
import efti.xml.fti.*
import efti.xml.fti.FTIResponseCode.Completed
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.HttpExchange
import klite.annotations.POST
import klite.nodes.at
import klite.uuid

@Tag(name = "Dataset query", description = "These routes are for mapping requests and responses for dataset query.")
class DatasetRoutes(val requestIdHandler: RequestIdHandler) {
  @Operation(description = "Map UIL and SubsetIds as JSON to FTI009GetCmdsRequest as XML.")
  @POST("/request-to-xml") fun requestToXml(req: DatasetQueryRequest, e: HttpExchange): String =
    FTI009GetCmdsRequest(ExchangedDocument("009", e.requestId.uuid), req.subsets, req.uil).render()

  @Operation(description = "Map FTI009GetCmdsRequest as XML to UIL and SubsetIds as JSON.")
  @POST("/request-to-json") fun requestToJson(xml: String, e: HttpExchange): DatasetQueryRequest {
    val req = xmlParser.parse<FTI009GetCmdsRequest>(xml)
    requestIdHandler.send(e, req.document.queryId)
    return DatasetQueryRequest(req.uil, req.subsets)
  }

  @Operation(description = "Map FTI010GetCmdsResponse or SpecifiedSupplyChainConsignment as XML to FTI010GetCmdsResponse as XML.")
  @POST("/response-to-xml") fun responseToXml(req: DatasetResponse, e: HttpExchange): String {
    if (req.xml.contains("ExchangedDocument")) return req.xml
    return FTI010GetCmdsResponse(ExchangedDocument("010", e.requestId.uuid, responseCode = Completed), req.subsets, req.uil).render(req.xml)
  }

  @Operation(description = "Map FTI010GetCmdsResponse or SpecifiedSupplyChainConsignment as XML to SpecifiedSupplyChainConsignment as JSON.")
  @POST("/response-to-json") fun responseToJson(xml: String): AuthorityDatasetResponse {
    val xml = xml.extractSpecifiedSupplyChainConsignment()
    val parsed = xmlParser.parseNodes(xml)
    return AuthorityDatasetResponse(xml, parsed.at("specifiedSupplyChainConsignment"))
  }
}

data class DatasetQueryRequest(val uil: UIL, val subsets: List<Subset>)

data class DatasetResponse(val uil: UIL, val xml: String, val subsets: List<Subset> = emptyList())

data class AuthorityDatasetResponse(val xml: String, val consignment: SpecifiedSupplyChainConsignment)