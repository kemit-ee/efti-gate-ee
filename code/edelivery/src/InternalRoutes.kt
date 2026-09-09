import edelivery.EDeliveryClient
import edelivery.PartyId
import edelivery.RequestKey
import edelivery.UserMessageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import klite.HttpExchange
import klite.MimeTypes
import klite.annotations.POST
import klite.annotations.PathParam
import klite.uuid

@Tag(name = "Internal routes", description = "Meant for Ruuter and Multiplexer.")
class InternalRoutes(
  private val eDeliveryClient: EDeliveryClient,
  private val partyRegistry: EDeliveryPartyRegistry
) {
  @Operation(summary = "Send an eDelivery message", description = "Sends an eFTI XML message to the selected eDelivery party and waits for its XML response.")
  @RequestBody(description = "eFTI XML message payload", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @ApiResponse(responseCode = "200", description = "XML response returned by the receiving party", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @POST("/send/:partyId") fun send(xml: String, @PathParam partyId: PartyId, e: HttpExchange): String {
    val party = partyRegistry[partyId]
    return eDeliveryClient.sendAndReceive(party.eDeliveryUrl, UserMessageParams(RequestKey(partyId, e.requestId.uuid)), xml)
  }

  @Operation(summary = "Ping an eDelivery party", description = "Sends an eDelivery ping to verify connectivity with the selected party.")
  @ApiResponse(responseCode = "204", description = "The party responded successfully")
  @POST("/ping/:partyId") fun ping(@PathParam partyId: PartyId) {
    partyRegistry.reload()
    val party = partyRegistry[partyId]
    eDeliveryClient.ping(party)
  }
}
