import edelivery.AsyncResponseProvider
import edelivery.MessageContext
import edelivery.PartyId
import edelivery.RequestKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class EftiMessageHandlersTest {
  private val client = mockk<RuuterClient>()
  private val asyncResponseProvider = mockk<AsyncResponseProvider>(relaxed = true)
  private val handlers = EftiMessageHandlers(asyncResponseProvider, client)
  private val requestId = UUID.randomUUID()
  private val key = RequestKey(PartyId("platform-one"), requestId, PartyId("EU-EE"))

  private fun handle(rootTag: String, xml: String = "<xml/>") =
    handlers.rootTags.getValue(rootTag)(MessageContext(key, xml))

  @Test fun `hello is a no-op ping`() {
    assertNull(handle("hello"))
  }

  @Test fun `upload binds to the original inbound sender rather than our gate`() {
    every { client.saveConsignment(any(), any(), any()) } returns "<response/>"

    handle("FTI004UploadIdentifierRequest", "<upload/>")

    verify { client.saveConsignment("<upload/>", requestId, key.receiverId) }
  }

  @Test fun `getDataset request forwards to Ruuter using the sender as receiver`() {
    every { client.getDataset(any(), any(), any()) } returns "<dataset/>"

    val result = handle("FTI009GetCmdsRequest", "<get/>")

    assertEquals("<dataset/>", result)
    verify { client.getDataset("<get/>", requestId, key.senderId) }
  }

  @Test fun `searchConsignments request forwards to Ruuter using the sender as gate`() {
    every { client.searchConsignments(any(), any(), any()) } returns "<search-result/>"

    val result = handle("FTI019SearchIdentifierRequest", "<search/>")

    assertEquals("<search-result/>", result)
    verify { client.searchConsignments("<search/>", key.senderId, requestId) }
  }

  @Test fun `followUp request forwards to Ruuter using the sender as receiver`() {
    every { client.followUp(any(), any(), any()) } returns "<follow-up-result/>"

    val result = handle("FTI025LodgeFollowUpCommRequest", "<follow-up/>")

    assertEquals("<follow-up-result/>", result)
    verify { client.followUp("<follow-up/>", requestId, key.senderId) }
  }

  @Test fun `response messages hand the raw XML to the async response provider and return nothing`() {
    for (responseTag in listOf(
      "FTI010GetCmdsResponse", "FTI021SearchIdentifierResponse",
      "FTI029UploadIdentifierResponse", "FTI030LodgeFollowUpCommResponse",
    )) {
      assertNull(handle(responseTag, "<response-$responseTag/>"))
      verify { asyncResponseProvider.provideResponse(key, "<response-$responseTag/>") }
    }
  }
}
