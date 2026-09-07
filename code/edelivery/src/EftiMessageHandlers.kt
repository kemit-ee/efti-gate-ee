import edelivery.*
import klite.Config

class EftiMessageHandlers(
  private val asyncResponseProvider: AsyncResponseProvider,
  private val ruuterClient: RuuterClient,
  private val ownPartyId: PartyId = Config.partyId
): MessageHandlers {
  private fun provideResponse(ctx: MessageContext): String? {
    asyncResponseProvider.provideResponse(ctx.key, ctx.xml)
    return null
  }

  override val rootTags: Map<String, (MessageContext) -> String?> = mapOf(
    "hello" to { null },
    "FTI009GetCmdsRequest" to { ruuterClient.getDataset(it.xml, it.key.requestId) },
    "FTI010GetCmdsResponse" to ::provideResponse,
    "FTI019SearchIdentifierRequest" to { ruuterClient.searchConsignments(it.xml, it.key.senderId, it.key.requestId) },
    "FTI021SearchIdentifierResponse" to ::provideResponse,
    "FTI004UploadIdentifierRequest" to { ruuterClient.saveConsignment(it.xml, it.key.requestId) },
    "FTI029UploadIdentifierResponse" to ::provideResponse,
    "FTI025LodgeFollowUpCommRequest" to { ruuterClient.followUp(it.xml, it.key.requestId) },
    "FTI030LodgeFollowUpCommResponse" to ::provideResponse
  )
}
