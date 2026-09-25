package edelivery

import klite.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class RequestKeyTest {
  @Test fun `parses receiverId and requestId from a colon-joined string`() {
    val requestId = UUID.randomUUID()

    val key = RequestKey("EU-EE:$requestId")

    assertEquals(PartyId("EU-EE"), key.receiverId)
    assertEquals(requestId, key.requestId)
    assertEquals(Config.partyId, key.senderId)
  }
}
