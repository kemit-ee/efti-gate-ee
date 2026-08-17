import edelivery.EDeliveryClient
import edelivery.PartyId
import edelivery.RequestKey
import edelivery.UserMessageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.POST
import klite.annotations.PathParam

@Tag(name = "Internal routes", description = "Meant for Ruuter and Multiplexer.")
class InternalRoutes(
  private val eDeliveryClient: EDeliveryClient,
  private val partyRegistry: EDeliveryPartyRegistry
) {
  @Operation(description = "Send eDelivery message to given Party.")
  @POST("/send/:partyId") fun send(xml: String, @PathParam partyId: PartyId): String {
    val party = partyRegistry[partyId]
    return eDeliveryClient.sendAndReceive(party.eDeliveryUrl, UserMessageParams(RequestKey(partyId)), xml)
  }

  @Operation(description = "Ping eDelivery party to verify connectivity.")
  @POST("/ping/:partyId") fun ping(@PathParam partyId: PartyId) {
    val party = partyRegistry[partyId]
    eDeliveryClient.ping(party)
  }
}
