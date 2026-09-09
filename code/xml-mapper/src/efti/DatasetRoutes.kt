package efti

import RequestIdHandler
import efti.domain.UIL
import efti.subsets.Subset
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
import klite.annotations.POST
import klite.nodes.at
import klite.uuid

@Tag(name = "Dataset query", description = "These routes are for mapping requests and responses for dataset query.")
class DatasetRoutes(val requestIdHandler: RequestIdHandler) {
  @Operation(summary = "Create a dataset query request", description = "Maps a UIL and requested subsets to an FTI009GetCmdsRequest XML document.")
  @ApiResponse(responseCode = "200", description = "FTI009GetCmdsRequest XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/request-to-xml") fun requestToXml(req: DatasetQueryRequest, e: HttpExchange): String =
    FTI009GetCmdsRequest(ExchangedDocument("009", e.requestId.uuid), req.subsets, req.uil).render()

  @Operation(summary = "Parse a dataset query request", description = "Maps an FTI009GetCmdsRequest XML document to a UIL and requested subsets.")
  @RequestBody(description = "FTI009GetCmdsRequest XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/request-to-json") fun requestToJson(xml: String, e: HttpExchange): DatasetQueryRequest {
    val req = xmlParser.parse<FTI009GetCmdsRequest>(xml)
    requestIdHandler.send(e, req.document.queryId)
    return DatasetQueryRequest(req.uil, req.subsets)
  }

  @Operation(summary = "Create a dataset response", description = "Wraps a specified supply-chain consignment in an FTI010GetCmdsResponse XML document, or passes through an existing FTI010 response.")
  @RequestBody(description = "Dataset response JSON containing the UIL, optional subsets, and consignment XML", content = [Content(mediaType = MimeTypes.json)])
  @ApiResponse(responseCode = "200", description = "FTI010GetCmdsResponse XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/response-to-xml") fun responseToXml(req: DatasetResponse, e: HttpExchange): String {
    if (req.xml.contains("ExchangedDocument")) return req.xml
    return FTI010GetCmdsResponse(ExchangedDocument("010", e.requestId.uuid, responseCode = Completed), req.subsets, req.uil).render(req.xml)
  }

  @Operation(summary = "Parse a dataset response", description = "Extracts the specified supply-chain consignment from an FTI010GetCmdsResponse or standalone consignment XML document.")
  @RequestBody(description = "FTI010GetCmdsResponse or SpecifiedSupplyChainConsignment XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/response-to-json") fun responseToJson(xml: String): AuthorityDatasetResponse {
    val xml = xml.extractSpecifiedSupplyChainConsignment()
    val parsed = xmlParser.parseNodes(xml)
    return AuthorityDatasetResponse(xml, parsed.at("specifiedSupplyChainConsignment"))
  }
}

data class DatasetQueryRequest(val uil: UIL, val subsets: List<Subset>)

data class DatasetResponse(val uil: UIL, val xml: String, val subsets: List<Subset> = emptyList())

data class AuthorityDatasetResponse(val xml: String, val consignment: SpecifiedSupplyChainConsignment)
