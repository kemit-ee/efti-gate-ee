package dataset

import efti.xml.fti.SpecifiedSupplyChainConsignment
import efti.domain.UIL
import efti.subsets.Subset
import efti.xml.fti.ExchangedDocument
import efti.xml.fti.FTI009GetCmdsRequest
import efti.xml.fti.FtiCapitalize
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.POST
import klite.xml.XmlParser
import java.util.UUID

@Tag(name = "Dataset query", description = "These routes are for mapping requests and responses for dataset query.")
class DatasetRoutes {
  val xmlParser = XmlParser(keys = FtiCapitalize)

  @Operation(description = "Map UIL and SubsetIds as JSON to FTI009GetCmdsRequest as XML.")
  @POST("/request-to-xml") fun requestToXml(req: DatasetQueryRequest): String =
    FTI009GetCmdsRequest(ExchangedDocument("009", req.queryId), req.subsets, req.uil).render()

  @Operation(description = "Map FTI009GetCmdsRequest as XML to UIL and SubsetIds as JSON.")
  @POST("/request-to-json") fun requestToJson(xml: String): DatasetQueryRequest {
    val req = xmlParser.parse<FTI009GetCmdsRequest>(xml)
    return DatasetQueryRequest(req.uil, req.subsets, req.document.queryId)
  }

  @Operation(description = "Map FTI010GetCmdsResponse or SpecifiedSupplyChainConsignment as XML to FTI010GetCmdsResponse as XML.")
  @POST("/response-to-xml") fun responseToXml(xml: String): String {
    TODO("Implement")
  }

  @Operation(description = "Map FTI010GetCmdsResponse or SpecifiedSupplyChainConsignment as XML to SpecifiedSupplyChainConsignment as JSON.")
  @POST("/response-to-json") fun responseToJson(xml: String): SpecifiedSupplyChainConsignment {
    TODO("Implement")
  }
}

data class DatasetQueryRequest(val uil: UIL, val subsets: List<Subset>, val queryId: UUID = UUID.randomUUID())
