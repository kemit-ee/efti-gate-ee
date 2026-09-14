import edelivery.AsyncResponseProvider
import edelivery.MessageContext
import edelivery.PartyId
import edelivery.RequestKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class EftiMessageHandlersTest {
  @Test fun `upload binds to the original inbound sender rather than our gate`() {
    val client = mockk<RuuterClient>()
    val requestId = UUID.randomUUID()
    val platform = PartyId("platform-one")
    val key = RequestKey(platform, requestId, PartyId("EU-EE"))
    every { client.saveConsignment(any(), any(), any()) } returns "<response/>"
    val handlers = EftiMessageHandlers(mockk<AsyncResponseProvider>(), client)

    handlers.rootTags.getValue("FTI004UploadIdentifierRequest")(MessageContext(key, "<upload/>"))

    verify { client.saveConsignment("<upload/>", requestId, platform) }
  }
}
