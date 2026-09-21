package edelivery

import klite.*
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.security.InvalidAlgorithmParameterException
import java.security.Provider
import java.security.Security
import javax.xml.crypto.Data
import javax.xml.crypto.KeySelector
import javax.xml.crypto.OctetStreamData
import javax.xml.crypto.URIDereferencer
import javax.xml.crypto.XMLCryptoContext
import javax.xml.crypto.XMLStructure
import javax.xml.crypto.dsig.CanonicalizationMethod
import javax.xml.crypto.dsig.DigestMethod
import javax.xml.crypto.dsig.SignatureMethod
import javax.xml.crypto.dsig.TransformService
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.dsig.spec.TransformParameterSpec
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val swaAttachmentTransformUri = "http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Signature-Transform"
private const val verifierDsNs = "http://www.w3.org/2000/09/xmldsig#"
private const val verifierWsuNs = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"

/**
 * Our own generator digests the attachment's raw compressed bytes directly, with no real
 * transform applied — this identity TransformService just lets JSR-105 treat that declared
 * (non-standard, SwA profile) transform algorithm as a pass-through so the digest step sees
 * the same bytes we dereference for `cid:message`.
 */
class SwaAttachmentContentSignatureTransform : TransformService() {
  override fun init(params: TransformParameterSpec?) {
    if (params != null) throw InvalidAlgorithmParameterException("SwA attachment transform takes no parameters")
  }
  override fun init(parent: XMLStructure?, context: XMLCryptoContext?) {}
  override fun marshalParams(parent: XMLStructure?, context: XMLCryptoContext?) {}
  override fun getParameterSpec(): TransformParameterSpec? = null
  override fun isFeatureSupported(feature: String?): Boolean = false
  override fun transform(data: Data, context: XMLCryptoContext?): Data = data
  override fun transform(data: Data, context: XMLCryptoContext?, os: OutputStream): Data? {
    (data as OctetStreamData).octetStream.copyTo(os)
    return null
  }
}

private class SwaTransformProvider : Provider(
  "EftiSwaAttachmentTransform", "1.0", "SwA attachment identity transform for AS4 signature verification"
) {
  init {
    put("TransformService.$swaAttachmentTransformUri", SwaAttachmentContentSignatureTransform::class.java.name)
    put("TransformService.$swaAttachmentTransformUri MechanismType", "DOM")
  }
}

/**
 * Verifies the WS-Security XML signature on an inbound AS4 message (Epic #50 denial scenario:
 * "Invalid AS4 signature → rejected"). Uses the JDK's JSR-105 (javax.xml.crypto.dsig) engine for
 * real Exclusive-C14N canonicalization rather than the project's own `canonicalXml()` helper,
 * which is only a whitespace-normalizer safe for our own generator's self-consistent output — not
 * for verifying a signature produced by another AS4 stack (another gate, Domibus).
 */
class SignatureVerifier(private val keyManager: KeyManager) {
  private val log = logger()
  private val xmlSignatureFactory = XMLSignatureFactory.getInstance("DOM")
  private val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    setXIncludeAware(false)
    isExpandEntityReferences = false
    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
  }

  init { Security.addProvider(SwaTransformProvider()) }

  /**
   * @param xml the raw SOAP/AS4 XML string as received
   * @param attachmentBytes the decrypted-but-not-yet-decompressed payload bytes (what the sender
   *   actually digested for the `cid:message` reference — see EDeliveryMessageGenerator.requestMessage)
   * @param header the already-parsed message header, for the sender identity used in error logging
   *   and to resolve the trusted verification key
   */
  fun verify(xml: String, attachmentBytes: ByteArray, header: MessageHeader) {
    // Same per-party cert field used to pick the RSA-OAEP encryption target — one cert per
    // party serves both purposes in this profile, so it doubles as the signing-cert source here.
    val senderCert = keyManager.receiverCert(header.senderId)

    val document = documentBuilderFactory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
    registerIdAttributes(document.documentElement)

    val signatureElement = document.getElementsByTagNameNS(verifierDsNs, "Signature").item(0) as? Element
      ?: throw SecurityException("No AS4 signature found in message from ${header.senderId}")

    val defaultDereferencer = xmlSignatureFactory.uriDereferencer
    val validateContext = DOMValidateContext(KeySelector.singletonKeySelector(senderCert.publicKey), signatureElement)
    validateContext.uriDereferencer = URIDereferencer { ref, context ->
      if (ref.uri == "cid:message") OctetStreamData(ByteArrayInputStream(attachmentBytes))
      else defaultDereferencer.dereference(ref, context)
    }

    val signature = xmlSignatureFactory.unmarshalXMLSignature(validateContext)
    val signedInfo = signature.signedInfo

    if (signedInfo.canonicalizationMethod.algorithm != CanonicalizationMethod.EXCLUSIVE)
      throw SecurityException("Unsupported canonicalization method from ${header.senderId}: ${signedInfo.canonicalizationMethod.algorithm}")
    if (signedInfo.signatureMethod.algorithm != SignatureMethod.RSA_SHA256)
      throw SecurityException("Unsupported signature method from ${header.senderId}: ${signedInfo.signatureMethod.algorithm}")
    signedInfo.references.forEach {
      if (it.digestMethod.algorithm != DigestMethod.SHA256)
        throw SecurityException("Unsupported digest method from ${header.senderId}: ${it.digestMethod.algorithm}")
    }

    if (!signature.validate(validateContext)) {
      log.warn("Invalid AS4 signature from ${header.senderId}")
      throw SecurityException("AS4 signature validation failed for message from ${header.senderId}")
    }
  }

  /** Registers every wsu:Id attribute in the document as an XML ID, so same-document `URI="#..."`
   *  references resolve regardless of what ID value the sender's AS4 stack chose. */
  private fun registerIdAttributes(node: Node) {
    if (node is Element && node.hasAttributeNS(verifierWsuNs, "Id")) node.setIdAttributeNS(verifierWsuNs, "Id", true)
    val children = node.childNodes
    for (i in 0 until children.length) registerIdAttributes(children.item(i))
  }
}
