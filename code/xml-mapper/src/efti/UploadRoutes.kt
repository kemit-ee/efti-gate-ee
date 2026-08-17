package efti

import efti.domain.UIL
import efti.xml.fti.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.POST
import java.util.*

@Tag(
  name = "Consignment upload",
  description = "These routes are for mapping requests and responses for consignment uploads."
)
class UploadRoutes {
  @Operation(description = "Map FTI004UploadIdentifierRequest or UniqueIDSetUIL as XML to UniqueIDSetUIL as JSON.")
  @POST("/request-to-json") fun requestToJson(xml: String): UniqueIDSetUIL =
    if (xml.contains("FTI004UploadIdentifierRequest")) xmlParser.parse<FTI004UploadIdentifierRequest>(xml).content
    else xmlParser.parse<UniqueIDSetUIL>(xml)

  @Operation(description = "Map UIL as JSON to FTI029UploadIdentifierResponse as XML.")
  @POST("/response-to-xml") fun responseToXml(uil: UIL): String =
    // TODO: queryId should come from somewhere
    FTI029UploadIdentifierResponse(ExchangedDocument("029", UUID.randomUUID()), uil).render()
}
