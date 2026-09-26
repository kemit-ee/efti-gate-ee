package edelivery

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class MessageHeaderTest {
  @Test fun `omitted optional fields default to null`() {
    val header = MessageHeader(
      senderId = PartyId("sender"),
      receiverId = PartyId("receiver"),
      messageId = "msg-1",
      conversationId = UUID.randomUUID(),
      keyEncryptionAlgorithm = "RSA-OAEP",
      dataEncryptionAlgorithm = "AES-GCM",
      references = emptyList(),
    )

    assertNull(header.keyIdentifier)
    assertNull(header.serialNumber)
    assertNull(header.cipherValue)
    assertNull(header.compressionType)
  }
}
