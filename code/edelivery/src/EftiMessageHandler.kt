import edelivery.AsyncResponseProvider
import edelivery.MessageContext
import edelivery.MessageHandler

class EftiMessageHandler(
  private val asyncResponseProvider: AsyncResponseProvider,
  private val ruuterClient: RuuterClient
): MessageHandler {
  private fun handleAsync(ctx: MessageContext): String? {
    asyncResponseProvider.provideResponse(ctx.key, ctx.xml)
    return null
  }

  override val handlers: Map<String, (MessageContext) -> String?> = mapOf(
    "FTI010GetCmdsResponse" to { ctx -> handleAsync(ctx) },
    "FTI021SearchIdentifierResponse" to { ctx -> handleAsync(ctx) },
    "FTI029UploadIdentifierResponse" to { ctx -> handleAsync(ctx) },
    "FTI030LodgeFollowUpCommResponse" to { ctx -> handleAsync(ctx) },
    "FTI009GetCmdsRequest" to { ruuterClient.getDataset(it.xml) },
    "FTI019SearchIdentifierRequest" to { ruuterClient.searchConsignments(it.xml) },
    "FTI004UploadIdentifierRequest" to { ruuterClient.saveConsignment(it.xml) },
    "FTI025LodgeFollowUpCommRequest" to { ruuterClient.followUp(it.xml) }
  )
}