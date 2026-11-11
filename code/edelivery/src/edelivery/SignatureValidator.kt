package edelivery

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.security.InvalidAlgorithmParameterException
import java.security.Provider
import java.security.Security
import java.security.cert.X509Certificate
import javax.xml.XMLConstants
import javax.xml.crypto.Data
import javax.xml.crypto.KeySelector
import javax.xml.crypto.OctetStreamData
import javax.xml.crypto.URIDereferencer
import javax.xml.crypto.XMLCryptoContext
import javax.xml.crypto.XMLStructure
import javax.xml.crypto.dsig.CanonicalizationMethod.EXCLUSIVE
import javax.xml.crypto.dsig.DigestMethod.SHA256
import javax.xml.crypto.dsig.SignatureMethod.RSA_SHA256
import javax.xml.crypto.dsig.TransformService
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.dsig.spec.TransformParameterSpec
import javax.xml.parsers.DocumentBuilderFactory

private const val swaAttachmentTransformUri = "http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Signature-Transform"
private const val verifierDsNs = "http://www.w3.org/2000/09/xmldsig#"
private const val verifierSoapNs = "http://www.w3.org/2003/05/soap-envelope"
private const val verifierWsseNs = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
private const val verifierEbNs = "http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/"
private const val verifierWsuNs = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"

class SignatureValidator {
  private val xmlSignatureFactory = XMLSignatureFactory.getInstance("DOM")
  // Legacy peer-XML parser, not reached in turvis mode (Turvis is then the only parser of peer bytes, SPEC 9.3); deleted by #312. Audit sheet: docs/architecture/security-and-compliance/perimeter-trust-model.md
  private val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    isXIncludeAware = false
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
  }

  init { Security.addProvider(SwaTransformProvider()) }

  fun verify(xml: String, header: MessageHeader, signerCert: X509Certificate, attachmentBytes: ByteArray) {
    verifySignature(xml, attachmentBytes, signerCert, "UserMessage")
    // TODO use fast verify that also checks digests in signedinfo
  }

  /** The same structural and signature checks for a signed receipt: `eb:SignalMessage` instead of `eb:UserMessage`, no attachment. */
  internal fun verifyReceipt(xml: String, signerCert: X509Certificate) = verifySignature(xml, ByteArray(0), signerCert, "SignalMessage")

  private fun verifySignature(xml: String, attachmentBytes: ByteArray, signerCert: X509Certificate, messageElement: String) {
    val document = documentBuilderFactory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
    registerIdAttributes(document.documentElement, mutableSetOf())

    val signatureElement = document.getElementsByTagNameNS(verifierDsNs, "Signature").item(0) as? Element
      ?: throw SecurityException("No AS4 signature found in message")
    val messaging = requireSignedStructure(document, messageElement)

    val defaultDereferencer = xmlSignatureFactory.uriDereferencer
    val validateContext = DOMValidateContext(KeySelector.singletonKeySelector(signerCert.publicKey), signatureElement).apply {
      uriDereferencer = URIDereferencer { ref, context ->
        if (ref.uri == "cid:message") OctetStreamData(ByteArrayInputStream(attachmentBytes))
        else defaultDereferencer.dereference(ref, context)
      }
    }

    val signature = xmlSignatureFactory.unmarshalXMLSignature(validateContext)
    val signedInfo = signature.signedInfo
    if (signedInfo.canonicalizationMethod.algorithm != EXCLUSIVE)
      throw SecurityException("Unsupported canonicalization method ${signedInfo.canonicalizationMethod.algorithm}")
    if (signedInfo.signatureMethod.algorithm != RSA_SHA256)
      throw SecurityException("Unsupported signature method ${signedInfo.signatureMethod.algorithm}")
    signedInfo.references.forEach {
      if (it.digestMethod.algorithm != SHA256) throw SecurityException("Unsupported digest method ${it.digestMethod.algorithm}")
    }

    val signedUris = signedInfo.references.map { it.uri }.toSet()
    val messagingId = messaging.getAttributeNS(verifierWsuNs, "Id")
    if (messagingId.isEmpty() || "#$messagingId" !in signedUris) throw SecurityException("The eb:Messaging element is not covered by the signature")
    if (messageElement == "UserMessage" && "cid:message" !in signedUris) throw SecurityException("The attachment is not covered by the signature")

    if (!signature.validate(validateContext)) throw SecurityException("Invalid message signature")
  }

  /**
   * The header fields are read by path (`Header/Messaging/...`, `Header/Security/Signature/...`) and the signature is validated
   * on the DOM, so both must be the same, single element: one eb:Messaging directly under env:Header, one ds:Signature directly
   * under wsse:Security under env:Header (any namespace for the counted local names, the path reader ignores namespaces).
   */
  private fun requireSignedStructure(document: Document, messageElement: String): Element {
    fun single(name: String) = document.getElementsByTagNameNS("*", name).let {
      if (it.length != 1) throw SecurityException("Expected exactly one $name element, found ${it.length}")
      it.item(0) as Element
    }
    val messaging = single("Messaging")
    val message = single(messageElement)
    val signature = single("Signature")
    if (messaging.namespaceURI != verifierEbNs) throw SecurityException("The eb:Messaging element is not in the ebMS namespace")
    if (message.parentNode != messaging) throw SecurityException("The eb:$messageElement element is not a child of eb:Messaging")
    val header = messaging.parentNode as? Element
    val envelope = document.documentElement
    if (header == null || header.namespaceURI != verifierSoapNs || header.localName != "Header" || header.parentNode != envelope ||
      envelope.namespaceURI != verifierSoapNs || envelope.localName != "Envelope")
      throw SecurityException("The eb:Messaging element is not a child of env:Header")
    val security = signature.parentNode as? Element
    if (signature.namespaceURI != verifierDsNs || security == null || security.namespaceURI != verifierWsseNs || security.localName != "Security" || security.parentNode != header)
      throw SecurityException("The signature is not a child of wsse:Security in env:Header")
    return messaging
  }

  private fun registerIdAttributes(node: Node, seen: MutableSet<String>) {
    if (node is Element && node.hasAttributeNS(verifierWsuNs, "Id")) {
      if (!seen.add(node.getAttributeNS(verifierWsuNs, "Id"))) throw SecurityException("Duplicate wsu:Id")
      node.setIdAttributeNS(verifierWsuNs, "Id", true)
    }
    val children = node.childNodes
    for (i in 0 until children.length) registerIdAttributes(children.item(i), seen)
  }
}


private class SwaTransformProvider : Provider(
  "EftiSwaAttachmentTransform", "1.0", "SwA attachment identity transform for AS4 signature verification"
) {
  init {
    put("TransformService.$swaAttachmentTransformUri", SwaAttachmentContentSignatureTransform::class.java.name)
    put("TransformService.$swaAttachmentTransformUri MechanismType", "DOM")
  }

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
}


