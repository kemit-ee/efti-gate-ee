package edelivery

import klite.xml.XmlPath
import java.util.*

data class MessageHeader(
  @XmlPath("Header/Messaging/UserMessage/PartyInfo/From/PartyId") val senderId: PartyId,
  @XmlPath("Header/Messaging/UserMessage/PartyInfo/To/PartyId") val receiverId: PartyId,
  @XmlPath("Header/Messaging/UserMessage/MessageInfo/MessageId") val messageId: String,
  @XmlPath("Header/Messaging/UserMessage/CollaborationInfo/ConversationId") val conversationId: String?,
  @XmlPath("Header/Security/EncryptedKey/EncryptionMethod/@Algorithm") val keyEncryptionAlgorithm: String,
  @XmlPath("Header/Security/EncryptedKey/KeyInfo/SecurityTokenReference/KeyIdentifier") val keyIdentifier: String? = null,
  @XmlPath("Header/Security/EncryptedKey/KeyInfo/SecurityTokenReference/X509Data/X509IssuerSerial/X509SerialNumber") val serialNumber: String? = null,
  @XmlPath("Header/Security/EncryptedKey/CipherData/CipherValue") val cipherValue: String? = null,
  @XmlPath("Header/Security/EncryptedData/EncryptionMethod/@Algorithm") val dataEncryptionAlgorithm: String,
  @XmlPath("Header/Security/Signature/SignedInfo/Reference") val references: List<SignatureReference>,
  @XmlPath("Header/Messaging/UserMessage/PayloadInfo/PartInfo/PartProperties/Property[@name=CompressionType]") val compressionType: String? = null
) {
  /**
   * ebMS3 allows any ConversationId string, but our request key and the Ruuter x-request-id are UUIDs, so a non-UUID one
   * maps to a stable name-based UUID. Only for internal correlation and log names; the peer's own string is echoed back.
   * A missing, blank or oversize id is refused (IllegalArgumentException, answered as XML_PARSE_FAIL).
   */
  val requestId: UUID get() = ConversationIds.requestId(conversationId)

  data class SignatureReference(
    @XmlPath("@URI") val uri: String,
    @XmlPath("DigestValue") val digestValue: String,
    @XmlPath("DigestMethod/@Algorithm") val digestMethod: String? = null,
    @XmlPath("Transforms/Transform") val transforms: List<Transform> = emptyList()
  )

  data class Transform(
    @XmlPath("@Algorithm") val algorithm: String,
    @XmlPath("InclusiveNamespaces/@PrefixList") val prefixList: String? = null
  )

  companion object {
    const val MAX_CONVERSATION_ID_LENGTH = ConversationIds.MAX_LENGTH
  }
}
