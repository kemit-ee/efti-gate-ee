package dataset

import efti.xml.fti.SpecifiedSupplyChainConsignment
import efti.domain.UIL
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.POST

@Tag(name = "Dataset query", description = "These routes are for mapping requests and responses for dataset query.")
class DatasetRoutes {
  @Operation(description = "Map UIL and SubsetIds as JSON to FTI009GetCmdsRequest as XML.")
  @POST("/request-to-xml") fun requestToXml(request: DatasetQueryRequest): String {
    TODO("Implement")
  }

  @Operation(description = "Map FTI009GetCmdsRequest as XML to UIL and SubsetIds as JSON.")
  @POST("/request-to-json") fun requestToJson(xml: String): DatasetQueryRequest {
    TODO("Implement")
  }

  @Operation(description = "Map FTI010GetCmdsResponse or SpecifiedSupplyChainConsignment as XML to SpecifiedSupplyChainConsignment as JSON.")
  @POST("/response-to-json") fun responseToJson(xml: String): SpecifiedSupplyChainConsignment {
    TODO("Implement")
  }

  @Operation(description = "Map FTI010GetCmdsResponse or SpecifiedSupplyChainConsignment as XML to FTI010GetCmdsResponse as XML.")
  @POST("/response-to-xml") fun responseToXml(xml: String): String {
    TODO("Implement")
  }
}

data class DatasetQueryRequest(val uil: UIL, val subsetIds: List<String>)
