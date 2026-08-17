package core

import klite.base64Encode
import org.intellij.lang.annotations.Language
import java.io.ByteArrayOutputStream
import java.lang.System.currentTimeMillis
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.MGF1ParameterSpec
import java.time.Instant
import java.util.UUID.randomUUID
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Cipher.ENCRYPT_MODE
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

private const val messagingWsuId = "MSG-ID"
private const val bodyWsuId = "BODY-ID"
private const val signatureId = "SIG-ID"
private const val encryptedKeyId = "EK-ID"
private const val encryptedDataId = "ED-ID"
private const val soapNs = "http://www.w3.org/2003/05/soap-envelope"
private const val wsuNs = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
const val wsseNs = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
private const val dsNs = "http://www.w3.org/2000/09/xmldsig#"
const val xencNs = "http://www.w3.org/2001/04/xmlenc#"
private const val xencSha256 = xencNs + "sha256"
private const val ebNs = "http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/"

/**
 * This class generates pre-normalized EDelivery XML messages (ordered tags and attributes, unclosed tags, etc)
 * that are ready for signing, so we can omit the expensive normalization process
 */
class EDeliveryMessageGenerator(
  private val keyManager: KeyManager,
) {
  private fun timestamp() = Instant.ofEpochMilli(currentTimeMillis())

  fun requestMessage(params: UserMessageParams, payload: String): Pair<String, ByteArray> {
    val compressedPayload = gzip(payload.toByteArray())
    val aesKey = generateAESKey()
    val attachmentBytes = encryptPayload(aesKey, compressedPayload)

    val messagingXml = userMessageXml(params)
    val encryptionXml = encryptedKeyXml(params.requestKey.receiverId, aesKey) + encryptedDataXml()

    val xml = signedSoapXml(messagingXml, emptySoapBody(), encryptionXml, compressedPayload)
    return Pair(xml, attachmentBytes)
  }

  internal fun generateAESKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  fun responseMessage(header: MessageHeader): String {
    val messagingXml = signalMessageXml(header)
    return signedSoapXml(messagingXml, emptySoapBody())
  }

  private fun signedSoapXml(messagingXml: String, bodyXml: String, securityContentXml: String = "", payloadBytes: ByteArray? = null): String {
    val signedInfoXml = signedInfoXml(messagingXml, bodyXml, payloadBytes)

    val sig = Signature.getInstance("SHA256withRSA")
    sig.initSign(keyManager.ownPrivateKey)
    sig.update(signedInfoXml.toByteArray())
    val signatureB64 = sig.sign().base64Encode()

    return /* language=xml */ soapEnvelope(
      messagingXml + """
    <wsse:Security xmlns:wsse="$wsseNs" xmlns:wsu="$wsuNs" env:mustUnderstand="true">
      $securityContentXml
      <ds:Signature xmlns:ds="$dsNs" Id="$signatureId">
        $signedInfoXml
        <ds:SignatureValue>$signatureB64</ds:SignatureValue>
        <ds:KeyInfo Id="KI-${randomUUID()}">${securityTokenReference(keyManager.ownCertSki)}</ds:KeyInfo>
      </ds:Signature>
    </wsse:Security>
  """.canonicalXml(), bodyXml)
  }

  @Language("xml") fun soapEnvelope(@Language("xml") headerContentXml: String, @Language("xml") fullBodyXml: String? = null) =
    """<env:Envelope xmlns:env="$soapNs">""" +
      "<env:Header>${headerContentXml}</env:Header>" +
      fullBodyXml +
    "</env:Envelope>"

  @Language("xml") private fun securityTokenReference(ski: String) = """
    <wsse:SecurityTokenReference>
      <wsse:KeyIdentifier EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary" ValueType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509SubjectKeyIdentifier">
        $ski
      </wsse:KeyIdentifier>
    </wsse:SecurityTokenReference>
  """

  internal fun encryptAesKey(encryptionCert: X509Certificate, aesKey: SecretKey): ByteArray {
    val encryptionKey = encryptionCert.publicKey as PublicKey
    val oaepSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
    val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
    rsaCipher.init(ENCRYPT_MODE, encryptionKey, oaepSpec)
    return rsaCipher.doFinal(aesKey.encoded)
  }

  internal fun encryptPayload(aesKey: SecretKey, compressedPayload: ByteArray): ByteArray {
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val gcmSpec = GCMParameterSpec(128, iv)
    val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
    aesCipher.init(ENCRYPT_MODE, aesKey, gcmSpec)
    val encryptedPayload = aesCipher.doFinal(compressedPayload)
    val attachmentBytes = iv + encryptedPayload
    return attachmentBytes
  }

  internal fun gzip(bytes: ByteArray): ByteArray {
    val baos = ByteArrayOutputStream()
    GZIPOutputStream(baos).use { it.write(bytes) }
    return baos.toByteArray()
  }

  @Language("xml")
  private fun encryptedKeyXml(partyId: PartyId<*>, aesKey: SecretKey): String {
    val receiverCert = keyManager.receiverCert(partyId)
    val encryptedAesKeyB64 = encryptAesKey(receiverCert, aesKey).base64Encode()

    return """
  <xenc:EncryptedKey xmlns:xenc="$xencNs" Id="$encryptedKeyId">
    <xenc:EncryptionMethod Algorithm="http://www.w3.org/2009/xmlenc11#rsa-oaep">
      <ds:DigestMethod xmlns:ds="$dsNs" Algorithm="$xencSha256"></ds:DigestMethod>
      <xenc11:MGF xmlns:xenc11="http://www.w3.org/2009/xmlenc11#" Algorithm="http://www.w3.org/2009/xmlenc11#mgf1sha256"></xenc11:MGF>
    </xenc:EncryptionMethod>
    <ds:KeyInfo xmlns:ds="$dsNs">${securityTokenReference(keyManager.certSki(receiverCert))}</ds:KeyInfo>
    <xenc:CipherData>
      <xenc:CipherValue>$encryptedAesKeyB64</xenc:CipherValue>
    </xenc:CipherData>
    <xenc:ReferenceList>
      <xenc:DataReference URI="#$encryptedDataId"></xenc:DataReference>
    </xenc:ReferenceList>
  </xenc:EncryptedKey>
  """
  }

  @Language("xml") private fun emptySoapBody() =
    """<env:Body xmlns:env="$soapNs" xmlns:wsu="$wsuNs" wsu:Id="$bodyWsuId"></env:Body>"""

  private fun signedInfoXml(messagingXml: String, bodyXml: String, payloadBytes: ByteArray?): String {
    val sha256 = MessageDigest.getInstance("SHA-256")
    return /* language=xml */ """
    <ds:SignedInfo xmlns:ds="$dsNs" xmlns:env="$soapNs">
      <ds:CanonicalizationMethod Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#">
        <ec:InclusiveNamespaces xmlns:ec="http://www.w3.org/2001/10/xml-exc-c14n#" PrefixList="env"></ec:InclusiveNamespaces>
      </ds:CanonicalizationMethod>
      <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"></ds:SignatureMethod>
      <ds:Reference URI="#$messagingWsuId">
        <ds:Transforms>
          <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#">
            <ec:InclusiveNamespaces xmlns:ec="http://www.w3.org/2001/10/xml-exc-c14n#" PrefixList="env"></ec:InclusiveNamespaces>
          </ds:Transform>
        </ds:Transforms>
        <ds:DigestMethod Algorithm="$xencSha256"></ds:DigestMethod>
        <ds:DigestValue>${sha256.digest(messagingXml.toByteArray()).base64Encode()}</ds:DigestValue>
      </ds:Reference>
      <ds:Reference URI="#$bodyWsuId">
        <ds:Transforms>
          <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"></ds:Transform>
        </ds:Transforms>
        <ds:DigestMethod Algorithm="$xencSha256"></ds:DigestMethod>
        <ds:DigestValue>${sha256.digest(bodyXml.toByteArray()).base64Encode()}</ds:DigestValue>
      </ds:Reference>
      ${payloadBytes?.let { """
      <ds:Reference URI="cid:message">
        <ds:Transforms>
          <ds:Transform Algorithm="http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Signature-Transform"></ds:Transform>
        </ds:Transforms>
        <ds:DigestMethod Algorithm="$xencSha256"></ds:DigestMethod>
        <ds:DigestValue>${sha256.digest(payloadBytes).base64Encode()}</ds:DigestValue>
      </ds:Reference>
      """ } ?: ""}
    </ds:SignedInfo>
    """.canonicalXml()
  }

  @Language("xml")
  private fun encryptedDataXml() = """
<xenc:EncryptedData xmlns:xenc="$xencNs" Id="$encryptedDataId" MimeType="application/gzip" Type="http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Only">
  <xenc:EncryptionMethod Algorithm="http://www.w3.org/2009/xmlenc11#aes128-gcm"></xenc:EncryptionMethod>
  <ds:KeyInfo xmlns:ds="$dsNs">
    <wsse:SecurityTokenReference xmlns:wsse="$wsseNs" wsse:TokenType="http://docs.oasis-open.org/wss/oasis-wss-soap-message-security-1.1#EncryptedKey">
      <wsse:Reference URI="#$encryptedKeyId"></wsse:Reference>
    </wsse:SecurityTokenReference>
  </ds:KeyInfo>
  <xenc:CipherData>
    <xenc:CipherReference URI="cid:message">
      <xenc:Transforms>
        <ds:Transform xmlns:ds="$dsNs" Algorithm="http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Ciphertext-Transform"></ds:Transform>
      </xenc:Transforms>
    </xenc:CipherReference>
  </xenc:CipherData>
</xenc:EncryptedData>
""".canonicalXml()

  @Language("xml")
  private fun userMessageXml(params: UserMessageParams) = """
<eb:Messaging xmlns:eb="$ebNs" xmlns:env="$soapNs" xmlns:wsu="$wsuNs" wsu:Id="$messagingWsuId" env:mustUnderstand="true">
  <eb:UserMessage>
    <eb:MessageInfo>
      <eb:Timestamp>${timestamp()}</eb:Timestamp>
      <eb:MessageId>${params.requestKey.requestId}@${keyManager.partyId}</eb:MessageId>
    </eb:MessageInfo>
    <eb:PartyInfo>
      <eb:From>
        <eb:PartyId type="urn:oasis:names:tc:ebcore:partyid-type:unregistered">${params.requestKey.senderId}</eb:PartyId>
        <eb:Role>${ebNs}initiator</eb:Role>
      </eb:From>
      <eb:To>
        <eb:PartyId type="urn:oasis:names:tc:ebcore:partyid-type:unregistered">${params.requestKey.receiverId}</eb:PartyId>
        <eb:Role>${ebNs}responder</eb:Role>
      </eb:To>
    </eb:PartyInfo>
    <eb:CollaborationInfo>
      <eb:Service${params.serviceType?.let { " type=\"$it\"" } ?: ""}>${params.service}</eb:Service>
      <eb:Action>${params.action}</eb:Action>
      <eb:ConversationId>${params.requestKey.requestId}</eb:ConversationId>
    </eb:CollaborationInfo>
    <eb:MessageProperties>
      <eb:Property name="originalSender" type="urn:oasis:names:tc:ebcore:partyid-type:iso6523:actorid-upis">iso6523-actorid-upis::9915:${keyManager.partyId}</eb:Property>
      <eb:Property name="finalRecipient" type="urn:oasis:names:tc:ebcore:partyid-type:iso6523:actorid-upis">iso6523-actorid-upis::9915:${params.requestKey.receiverId}</eb:Property>
    </eb:MessageProperties>
    <eb:PayloadInfo>
      <eb:PartInfo href="cid:message">
        <eb:PartProperties>
          <eb:Property name="MimeType">text/xml</eb:Property>
          <eb:Property name="CompressionType">application/gzip</eb:Property>
        </eb:PartProperties>
      </eb:PartInfo>
    </eb:PayloadInfo>
  </eb:UserMessage>
</eb:Messaging>
""".canonicalXml()

  private fun signalMessageXml(header: MessageHeader): String {
    val originalMessageId = header.messageId
    val references = header.references
    return /*language=xml*/ """
    <eb:Messaging xmlns:eb="$ebNs" xmlns:env="$soapNs" xmlns:wsu="$wsuNs" wsu:Id="$messagingWsuId" env:mustUnderstand="true">
      <eb:SignalMessage>
        <eb:MessageInfo>
          <eb:Timestamp>${timestamp()}</eb:Timestamp>
          <eb:MessageId>${randomUUID()}@${keyManager.partyId}</eb:MessageId>
          <eb:RefToMessageId>$originalMessageId</eb:RefToMessageId>
        </eb:MessageInfo>
        <eb:Receipt>
          <ebbp:NonRepudiationInformation xmlns:ebbp="http://docs.oasis-open.org/ebxml-bp/ebbp-signals-2.0">
            <ebbp:MessagePartNRInformation>
              <ds:Reference xmlns:ds="$dsNs" URI="${references[0].uri}">
                <ds:Transforms>
                  <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#">
                    <ec:InclusiveNamespaces xmlns:ec="http://www.w3.org/2001/10/xml-exc-c14n#" PrefixList="env"></ec:InclusiveNamespaces>
                  </ds:Transform>
                </ds:Transforms>
                <ds:DigestMethod Algorithm="$xencSha256"></ds:DigestMethod>
                <ds:DigestValue>${references[0].digestValue}</ds:DigestValue>
              </ds:Reference>
            </ebbp:MessagePartNRInformation>
            <ebbp:MessagePartNRInformation>
              <ds:Reference xmlns:ds="$dsNs" URI="${references[1].uri}">
                <ds:Transforms>
                  <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"></ds:Transform>
                </ds:Transforms>
                <ds:DigestMethod Algorithm="$xencSha256"></ds:DigestMethod>
                <ds:DigestValue>${references[1].digestValue}</ds:DigestValue>
              </ds:Reference>
            </ebbp:MessagePartNRInformation>
            <ebbp:MessagePartNRInformation>
              <ds:Reference xmlns:ds="$dsNs" URI="${references[2].uri}">
                <ds:Transforms>
                  <ds:Transform Algorithm="http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Signature-Transform"></ds:Transform>
                </ds:Transforms>
                <ds:DigestMethod Algorithm="$xencSha256"></ds:DigestMethod>
                <ds:DigestValue>${references[2].digestValue}</ds:DigestValue>
              </ds:Reference>
            </ebbp:MessagePartNRInformation>
          </ebbp:NonRepudiationInformation>
        </eb:Receipt>
      </eb:SignalMessage>
    </eb:Messaging>
    """.canonicalXml()
  }

  fun soapFault(message: String, ex: Exception) = soapEnvelope(
    """<eb:Messaging xmlns:eb="$ebNs" env:mustUnderstand="true">
      <eb:SignalMessage>
        <eb:MessageInfo>
          <eb:Timestamp>${timestamp()}</eb:Timestamp>
          <eb:MessageId>${randomUUID()}@${keyManager.partyId}</eb:MessageId>
        </eb:MessageInfo>
        <eb:Error category="CONTENT" errorCode="EBMS:0004" origin="ebMS" severity="failure" shortDescription="Other">
          <eb:Description xml:lang="en">Other</eb:Description>
          <eb:ErrorDetail>${message}</eb:ErrorDetail>
        </eb:Error>
      </eb:SignalMessage>
    </eb:Messaging>""",
    """<env:Body>
    <env:Fault>
      <env:Code><env:Value>env:Receiver</env:Value></env:Code>
      <env:Reason><env:Text xml:lang="en">${message}: ${ex.message}</env:Text></env:Reason>
    </env:Fault>
  </env:Body>"""
  ).canonicalXml()
}
