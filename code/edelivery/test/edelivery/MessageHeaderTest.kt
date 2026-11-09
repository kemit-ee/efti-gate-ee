package edelivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.UUID

class MessageHeaderTest {
  @Test fun `omitted optional fields default to null`() {
    val header = MessageHeader(
      senderId = PartyId("sender"),
      receiverId = PartyId("receiver"),
      messageId = "msg-1",
      conversationId = UUID.randomUUID().toString(),
      keyEncryptionAlgorithm = "RSA-OAEP",
      dataEncryptionAlgorithm = "AES-GCM",
      references = emptyList(),
    )

    assertNull(header.keyIdentifier)
    assertNull(header.serialNumber)
    assertNull(header.cipherValue)
    assertNull(header.compressionType)
  }

  private fun header(conversationId: String) = MessageHeader(
    senderId = PartyId("sender"), receiverId = PartyId("receiver"), messageId = "m", conversationId = conversationId,
    keyEncryptionAlgorithm = "RSA-OAEP", dataEncryptionAlgorithm = "AES-GCM", references = emptyList(),
  )

  @Test fun `a UUID conversation id is used as the request id unchanged`() {
    val id = UUID.randomUUID()
    assertEquals(id, header(id.toString()).requestId)
  }

  @Test fun `a non-UUID conversation id does not throw and maps to a stable request id`() {
    val a = header("conv-2026/abc").requestId
    assertEquals(a, header("conv-2026/abc").requestId)
    assertEquals(UUID.nameUUIDFromBytes("conv-2026/abc".toByteArray()), a)
  }

  @Test fun `blank and oversize conversation ids are refused`() {
    assertFailsWith<IllegalArgumentException> { header("  ").requestId }
    assertFailsWith<IllegalArgumentException> { header("").requestId }
    assertFailsWith<IllegalArgumentException> { header("x".repeat(256)).requestId }
  }

  @Test fun `a conversation id of exactly 255 characters is allowed`() {
    val id = "x".repeat(255)
    assertEquals(UUID.nameUUIDFromBytes(id.toByteArray()), header(id).requestId)
  }

  @Test fun `signature reference transforms and digest method are read from the SignedInfo`() {
    val h = klite.xml.XmlParser().parse<MessageHeader>(java.io.File("test/edelivery/soap-env.xml").readText())
    h.references.forEach { r ->
      assertTrue(r.transforms.isNotEmpty(), "transforms of ${r.uri}")
      assertTrue(r.transforms.all { it.algorithm.isNotBlank() })
      assertTrue(r.digestMethod!!.isNotBlank())
    }
  }

  @Test fun `a non-UUID ConversationId parses from the SOAP header`() {
    val xml = java.io.File("test/edelivery/soap-env.xml").readText()
      .replace("18911313-0e64-4c16-bec1-670a16648b9a", "not-a-uuid-42")
    assertEquals("not-a-uuid-42", klite.xml.XmlParser().parse<MessageHeader>(xml).conversationId)
  }
}
