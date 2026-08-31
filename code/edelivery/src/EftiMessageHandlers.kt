import edelivery.AsyncResponseProvider
import edelivery.MessageContext
import edelivery.MessageHandlers

class EftiMessageHandlers(
  private val asyncResponseProvider: AsyncResponseProvider,
  private val ruuterClient: RuuterClient
): MessageHandlers {
  private fun provideResponse(ctx: MessageContext): String? {
    asyncResponseProvider.provideResponse(ctx.key, ctx.xml)
    return null
  }

  override val rootTags: Map<String, (MessageContext) -> String?> = mapOf(
    "hello" to { null },
    "FTI009GetCmdsRequest" to { ruuterClient.getDataset(it.xml) },
    "FTI010GetCmdsResponse" to ::provideResponse,
    "FTI019SearchIdentifierRequest" to { ruuterClient.searchConsignments(it.xml) },
    "FTI021SearchIdentifierResponse" to ::provideResponse,
    "FTI004UploadIdentifierRequest" to { ruuterClient.saveConsignment(it.xml) },
    "FTI029UploadIdentifierResponse" to ::provideResponse,
    "FTI025LodgeFollowUpCommRequest" to { ruuterClient.followUp(it.xml) },
    "FTI030LodgeFollowUpCommResponse" to ::provideResponse
  )
}
