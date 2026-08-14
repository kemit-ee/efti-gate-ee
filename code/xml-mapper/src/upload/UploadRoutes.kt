package upload

import efti.domain.UIL
import efti.xml.fti.UniqueIDSetUIL
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.POST

@Tag(
  name = "Consignment upload",
  description = "These routes are for mapping requests and responses for consignment uploads."
)
class UploadRoutes {
  @Operation(description = "Map FTI004UploadIdentifierRequest or UniqueIDSetUIL as XML to UniqueIDSetUIL as JSON.")
  @POST("/request-to-json") fun requestToJson(xml: String): UniqueIDSetUIL {
    TODO("Implement")
  }

  @Operation(description = "Map UIL as JSON to FTI029UploadIdentifierResponse as XML.")
  @POST("/response-to-xml") fun responseToXml(uil: UIL): String {
    TODO("Implement")
  }
}
