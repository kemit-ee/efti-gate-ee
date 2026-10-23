package edelivery

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import klite.base64Encode
import org.junit.jupiter.api.Test
import resql.InboundMessageRow
import resql.ResqlClient
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboundDedupTest {
  private val resql = mockk<ResqlClient>()
  private val dedup = InboundDedup(resql)
  private val receipt = "<r>\r\n signed </r>".toByteArray() + byteArrayOf(0x0b, 0x00)

  @Test fun `the key is the upper-cased verified sender, so the case of a party id does not make a new message`() {
    val sender = slot<String>()
    every { resql.insertInboundMessage(capture(sender), any(), any(), any()) } returns true
    dedup.record(PartyId("eu-de"), "m-1", "abc", receipt)
    assertEquals("EU-DE", sender.captured)

    every { resql.findInboundMessage(capture(sender), any()) } returns null
    dedup.find(PartyId("Eu-De"), "m-1")
    assertEquals("EU-DE", sender.captured)
  }

  @Test fun `the receipt is stored and returned byte for byte`() {
    val stored = slot<String>()
    every { resql.insertInboundMessage(any(), any(), any(), capture(stored)) } returns true
    dedup.record(PartyId("DE"), "m-1", "abc", receipt)
    assertEquals(receipt.base64Encode(), stored.captured)

    every { resql.findInboundMessage("DE", "m-1") } returns InboundMessageRow(receipt.base64Encode(), "abc")
    val found = dedup.find(PartyId("DE"), "m-1")!!
    assertContentEquals(receipt, found.receipt)
    assertEquals("abc", found.payloadSha256)
  }

  @Test fun `a new message is not found, and record says whether this call recorded it`() {
    every { resql.findInboundMessage(any(), any()) } returns null
    assertNull(dedup.find(PartyId("DE"), "m-new"))

    every { resql.insertInboundMessage(any(), any(), any(), any()) } returnsMany listOf(true, false)
    assertTrue(dedup.record(PartyId("DE"), "m-1", "abc", receipt))
    assertEquals(false, dedup.record(PartyId("DE"), "m-1", "abc", receipt))
  }

  @Test fun `a store failure on read, on write or in a stored value is an outage, never a new message`() {
    every { resql.findInboundMessage(any(), any()) } throws java.io.IOException("reset")
    assertIs<java.io.IOException>(assertFailsWith<InboundDedupUnavailableException> { dedup.find(PartyId("DE"), "m-1") }.cause)

    every { resql.insertInboundMessage(any(), any(), any(), any()) } throws java.io.IOException("reset")
    assertFailsWith<InboundDedupUnavailableException> { dedup.record(PartyId("DE"), "m-1", "abc", receipt) }

    every { resql.findInboundMessage(any(), any()) } returns InboundMessageRow("!!not base64!!", "abc")
    assertFailsWith<InboundDedupUnavailableException> { dedup.find(PartyId("DE"), "m-1") }
  }

  @Test fun `a message id over the limit is refused as malformed before the store is touched, the limit itself is accepted`() {
    val id = "m".repeat(InboundDedup.MAX_MESSAGE_ID_LENGTH)
    every { resql.findInboundMessage(any(), any()) } returns null
    every { resql.insertInboundMessage(any(), any(), any(), any()) } returns true
    dedup.find(PartyId("DE"), id)
    dedup.record(PartyId("DE"), id, "abc", receipt)

    assertFailsWith<IllegalArgumentException> { dedup.find(PartyId("DE"), id + "m") }
    assertFailsWith<IllegalArgumentException> { dedup.record(PartyId("DE"), id + "m", "abc", receipt) }
    verify(exactly = 1) { resql.findInboundMessage(any(), any()) }
    verify(exactly = 1) { resql.insertInboundMessage(any(), any(), any(), any()) }
  }
}
