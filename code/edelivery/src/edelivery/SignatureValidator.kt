package edelivery

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
private const val verifierWsuNs = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"

class SignatureValidator {
  private val xmlSignatureFactory = XMLSignatureFactory.getInstance("DOM")
  private val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    isXIncludeAware = false
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
  }

  init { Security.addProvider(SwaTransformProvider()) }

  fun verify(xml: String, header: MessageHeader, signerCert: X509Certificate, attachmentBytes: ByteArray) {
    verifySignature(xml, attachmentBytes, signerCert)
    // TODO use fast verify that also checks digests in signedinfo
  }

  private fun verifySignature(xml: String, attachmentBytes: ByteArray, signerCert: X509Certificate) {
    val document = documentBuilderFactory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
    registerIdAttributes(document.documentElement)

    val signatureElement = document.getElementsByTagNameNS(verifierDsNs, "Signature").item(0) as? Element
      ?: throw SecurityException("No AS4 signature found in message")

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

    if (!signature.validate(validateContext)) throw SecurityException("Invalid message signature")
  }

  private fun registerIdAttributes(node: Node) {
    if (node is Element && node.hasAttributeNS(verifierWsuNs, "Id")) node.setIdAttributeNS(verifierWsuNs, "Id", true)
    val children = node.childNodes
    for (i in 0 until children.length) registerIdAttributes(children.item(i))
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


