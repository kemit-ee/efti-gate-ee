package core

import klite.xml.XmlPath

data class MessageHeader(
  @XmlPath("Header/Messaging/UserMessage/PartyInfo/From/PartyId") val senderId: PartyId<*>,
  @XmlPath("Header/Messaging/UserMessage/PartyInfo/To/PartyId") val receiverId: PartyId<*>,
  @XmlPath("Header/Messaging/UserMessage/MessageInfo/MessageId") val messageId: String,
  @XmlPath("Header/Security/EncryptedKey/EncryptionMethod/@Algorithm") val keyEncryptionAlgorithm: String,
  @XmlPath("Header/Security/EncryptedKey/KeyInfo/SecurityTokenReference/KeyIdentifier") val keyIdentifier: String? = null,
  @XmlPath("Header/Security/EncryptedKey/KeyInfo/SecurityTokenReference/X509Data/X509IssuerSerial/X509SerialNumber") val serialNumber: String? = null,
  @XmlPath("Header/Security/EncryptedKey/CipherData/CipherValue") val cipherValue: String? = null,
  @XmlPath("Header/Security/EncryptedData/EncryptionMethod/@Algorithm") val dataEncryptionAlgorithm: String,
  @XmlPath("Header/Security/Signature/SignedInfo/Reference") val references: List<SignatureReference>
) {
  data class SignatureReference(
    @XmlPath("@URI") val uri: String,
    @XmlPath("DigestValue") val digestValue: String
  )
}
