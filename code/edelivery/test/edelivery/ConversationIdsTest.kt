package edelivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ConversationIdsTest {
  private val canonical = "6f1c3a52-0000-4000-8000-00000000abcd"

  @Test fun `a canonical UUID is read as that UUID, in any case and trimmed`() {
    assertEquals(UUID.fromString(canonical), ConversationIds.requestId(canonical))
    assertEquals(UUID.fromString(canonical), ConversationIds.requestId(canonical.uppercase()))
    assertEquals(UUID.fromString(canonical), ConversationIds.requestId("  $canonical "))
  }

  @Test fun `anything else maps to a stable name-based UUID, including the lenient UUID spellings`() {
    for (id in listOf("fti-conv-00001", "1-1-1-1-1", "6f1c3a52-0000-4000-8000-00000000abc", "ä")) {
      assertEquals(UUID.nameUUIDFromBytes(id.toByteArray()), ConversationIds.requestId(id), id)
    }
    assertNotEquals(ConversationIds.requestId("1-1-1-1-1"), ConversationIds.requestId("00000001-0001-0001-0001-000000000001"))
  }

  @Test fun `canonicalUuid is null for a non-canonical id`() {
    assertEquals(null, ConversationIds.canonicalUuid("1-1-1-1-1"))
    assertEquals(null, ConversationIds.canonicalUuid(null))
    assertEquals(UUID.fromString(canonical), ConversationIds.canonicalUuid(canonical.uppercase()))
  }

  @Test fun `missing, blank and oversize ids are refused, 255 characters are allowed`() {
    for (bad in listOf(null, "", "   ", "c".repeat(256))) assertThrows<IllegalArgumentException> { ConversationIds.requestId(bad) }
    ConversationIds.requestId("c".repeat(255))
  }
}
