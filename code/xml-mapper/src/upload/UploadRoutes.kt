package upload

import efti.domain.UIL
import efti.xml.fti.ExchangedDocument
import efti.xml.fti.FTI004UploadIdentifierRequest
import efti.xml.fti.FTI029UploadIdentifierResponse
import efti.xml.fti.FtiCapitalize
import efti.xml.fti.UniqueIDSetUIL
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.POST
import klite.xml.XmlParser
import java.util.UUID

@Tag(
  name = "Consignment upload",
  description = "These routes are for mapping requests and responses for consignment uploads."
)
class UploadRoutes {
  val xmlParser = XmlParser(keys = FtiCapitalize)

  @Operation(description = "Map FTI004UploadIdentifierRequest or UniqueIDSetUIL as XML to UniqueIDSetUIL as JSON.")
  @POST("/request-to-json") fun requestToJson(xml: String): UniqueIDSetUIL {
    val req = xmlParser.parse<FTI004UploadIdentifierRequest>(xml)
    return req.content
  }

  @Operation(description = "Map UIL as JSON to FTI029UploadIdentifierResponse as XML.")
  @POST("/response-to-xml") fun responseToXml(uil: UIL): String =
    // TODO: queryId should come ffrom somewhere
    FTI029UploadIdentifierResponse(ExchangedDocument("029", UUID.randomUUID()), uil).render()
}
