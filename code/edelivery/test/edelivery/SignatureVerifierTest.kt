package edelivery

import io.mockk.every
import io.mockk.mockk
import klite.xml.XmlParser
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.spec.MGF1ParameterSpec
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertFailsWith

/** Direct unit tests for [SignatureVerifier], independent of the HTTP/decrypt plumbing in EDeliveryRoutes. */
class SignatureVerifierTest {
  private val senderKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
  private val receiverKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
  private val senderId = PartyId("sender1")
  private val receiverId = PartyId("receiver1")
  private val senderCert = mockk<X509Certificate> { every { publicKey } returns senderKey.public }
  private val receiverCert = mockk<X509Certificate> { every { publicKey } returns receiverKey.public }

  private val generatorKeyManager = mockk<KeyManager> {
    every { partyId } returns senderId
    every { ownPrivateKey } returns senderKey.private
    every { ownCertSki } returns "sender-ski"
    every { receiverCert(receiverId) } returns receiverCert
    every { certSki(receiverCert) } returns "receiver-ski"
  }
  private val generator = EDeliveryMessageGenerator(generatorKeyManager)

  private val verifierKeyManager = mockk<KeyManager> {
    every { receiverCert(senderId) } returns senderCert
  }
  private val signatureVerifier = SignatureVerifier(verifierKeyManager)

  private val xmlParser = XmlParser()

  /** Builds a genuinely signed+encrypted request message, and independently re-derives the exact
   *  compressed-plaintext attachment bytes (mirroring EDeliveryRoutes.decryptPayload) that the
   *  generator actually signed — needed because GZIPOutputStream embeds a timestamp, so gzip'ing
   *  the same payload again would not reproduce byte-identical (and thus digest-matching) output. */
  private fun buildSignedMessage(payload: String = "<data/>"): Triple<String, MessageHeader, ByteArray> {
    val (xml, encryptedAttachment) = generator.requestMessage(
      UserMessageParams(RequestKey(receiverId, UUID.randomUUID(), senderId)), payload
    )
    val header = xmlParser.parse<MessageHeader>(xml)

    val oaepSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
    val cipherRSA = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
    cipherRSA.init(Cipher.DECRYPT_MODE, receiverKey.private, oaepSpec)
    val aesKeyBytes = cipherRSA.doFinal(header.cipherValue!!.trim().base64Decode())
    val secretKey = SecretKeySpec(aesKeyBytes, "AES")

    val iv = encryptedAttachment.sliceArray(0 until 12)
    val ciphertext = encryptedAttachment.sliceArray(12 until encryptedAttachment.size)
    val cipherAES = Cipher.getInstance("AES/GCM/NoPadding")
    cipherAES.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
    val compressedPayload = cipherAES.doFinal(ciphertext)

    return Triple(xml, header, compressedPayload)
  }

  @Test fun `valid signature and digests pass`() {
    val (xml, header, attachmentBytes) = buildSignedMessage()
    signatureVerifier.verify(xml, attachmentBytes, header) // does not throw
  }

  @Test fun `wrong sender key is rejected`() {
    val (xml, header, attachmentBytes) = buildSignedMessage()
    val wrongCert = mockk<X509Certificate> {
      every { publicKey } returns KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
    }
    val wrongKeyVerifier = SignatureVerifier(mockk { every { receiverCert(senderId) } returns wrongCert })
    assertFailsWith<SecurityException> { wrongKeyVerifier.verify(xml, attachmentBytes, header) }
  }

  @Test fun `tampered SignedInfo reference content is rejected`() {
    val (xml, header, attachmentBytes) = buildSignedMessage()
    val tampered = xml.replaceFirst(Regex("(<eb:MessageId>)[^<]*"), "$1tampered-message-id")
    assertFailsWith<SecurityException> { signatureVerifier.verify(tampered, attachmentBytes, header) }
  }

  @Test fun `tampered SignatureValue is rejected`() {
    val (xml, header, attachmentBytes) = buildSignedMessage()
    val tampered = Regex("(<ds:SignatureValue>)([A-Za-z0-9+/=])").replace(xml) { m ->
      val flipped = if (m.groupValues[2] == "A") "B" else "A"
      m.groupValues[1] + flipped
    }
    assertFailsWith<SecurityException> { signatureVerifier.verify(tampered, attachmentBytes, header) }
  }

  @Test fun `wrong signature method algorithm is rejected`() {
    val (xml, header, attachmentBytes) = buildSignedMessage()
    // RSA-SHA1 would also be rejected, but as a jdk.xml.dsig-disabled algorithm it fails during
    // unmarshalling itself rather than exercising our own allowlist check — use SHA-384 instead,
    // which the JDK still constructs fine, to specifically test SignatureVerifier's own rejection.
    val tampered = xml.replace(
      "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256",
      "http://www.w3.org/2001/04/xmldsig-more#rsa-sha384"
    )
    assertFailsWith<SecurityException> { signatureVerifier.verify(tampered, attachmentBytes, header) }
  }

  @Test fun `tampered attachment digest is rejected`() {
    val (xml, header, attachmentBytes) = buildSignedMessage()
    val tampered = attachmentBytes + byteArrayOf(0)
    assertFailsWith<SecurityException> { signatureVerifier.verify(xml, tampered, header) }
  }

  @Test fun `missing signature element is rejected`() {
    val (xml, header, attachmentBytes) = buildSignedMessage()
    val stripped = xml.replace(Regex("<ds:Signature\\b.*?</ds:Signature>", RegexOption.DOT_MATCHES_ALL), "")
    assertFailsWith<SecurityException> { signatureVerifier.verify(stripped, attachmentBytes, header) }
  }
}

private fun String.base64Decode(): ByteArray = java.util.Base64.getDecoder().decode(this)
