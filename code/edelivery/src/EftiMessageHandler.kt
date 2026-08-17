import edelivery.AsyncResponseProvider
import edelivery.MessageHandler
import edelivery.RequestKey
import edelivery.from
import klite.info
import klite.logger

class EftiMessageHandler(
  private val asyncResponseProvider: AsyncResponseProvider,
  private val ruuterClient: RuuterClient
): MessageHandler {
  private val log = logger()
  private val rootTagRegex = "<\\s*(?:\\w+:)?(\\w+)".toRegex()

  override fun response(requestKey: RequestKey, xml: String): String? {
    val rootTag = rootTagRegex.from(xml)
    log.info("Handling $rootTag from $requestKey.")

    when (rootTag) {
      "FTI010GetCmdsResponse", "FTI021SearchIdentifierResponse",
      "FTI029UploadIdentifierResponse", "FTI030LodgeFollowUpCommResponse" -> {
        asyncResponseProvider.provideResponse(requestKey, xml)
      }
      "FTI009GetCmdsRequest" -> {
        return ruuterClient.getDataset(xml)
      }
      "FTI019SearchIdentifierRequest" -> {
        return ruuterClient.searchConsignments(xml)
      }
      "FTI004UploadIdentifierRequest" -> {
        return ruuterClient.saveConsignment(xml)
      }
      "FTI025LodgeFollowUpCommRequest" -> {
        return ruuterClient.followUp(xml)
      }

      else -> throw UnsupportedOperationException("Unknown root tag '$rootTag' from $requestKey")
    }
    return null
  }
}