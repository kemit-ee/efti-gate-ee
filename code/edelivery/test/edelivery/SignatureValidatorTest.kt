package edelivery

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import klite.base64Decode
import klite.base64Encode
import klite.xml.XmlParser
import org.junit.jupiter.api.Test
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows


class SignatureValidatorTest {
  val signatureValidator = SignatureValidator()
  val xml = File("test/edelivery/soap-env.xml").readText()

  val keyManager = spyk(KeyManager(mockk<PartyRegistry>(relaxUnitFun = true))).also {
    every { it.receiverCert(any()) } returns it.ownCert
  }
  val generator = EDeliveryMessageGenerator(keyManager)
  val xmlParser = XmlParser()

  private fun signedMessage(payload: String = "<data/>"): Triple<String, MessageHeader, ByteArray> {
    val (messageXml, _) = generator.requestMessage(
      UserMessageParams(RequestKey(keyManager.partyId, senderId = keyManager.partyId)), payload)
    return Triple(messageXml, xmlParser.parse<MessageHeader>(messageXml), generator.gzip(payload.toByteArray()))
  }

  @Test fun `verify accepts real generated message`() {
    val (messageXml, header, attachment) = signedMessage()
    assertDoesNotThrow { signatureValidator.verify(messageXml, header, keyManager.ownCert, attachment) }
  }

  @Test fun `verify rejects tampered attachment digest`() {
    val (messageXml, header, attachment) = signedMessage()
    val tampered = attachment.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
    assertThrows<SecurityException> { signatureValidator.verify(messageXml, header, keyManager.ownCert, tampered) }
  }

  @Test fun `verify rejects tampered message header digest`() {
    val (messageXml, header, attachment) = signedMessage()
    val tampered = messageXml.replace("<eb:Action>eftiGateAction</eb:Action>", "<eb:Action>tamperedAction</eb:Action>")
    assertThrows<SecurityException> { signatureValidator.verify(tampered, header, keyManager.ownCert, attachment) }
  }

  @Test fun `verify rejects tampered soap body digest`() {
    val (messageXml, header, attachment) = signedMessage()
    val tampered = messageXml.replaceFirst("></env:Body>", "><x/></env:Body>")
    assertThrows<SecurityException> { signatureValidator.verify(tampered, header, keyManager.ownCert, attachment) }
  }

  @Test fun `verify rejects tampered signature value`() {
    val (messageXml, header, attachment) = signedMessage()
    val match = Regex("<ds:SignatureValue>([^<]+)</ds:SignatureValue>").find(messageXml)!!
    val signature = match.groupValues[1].base64Decode()
    val tampered = signature.copyOf().also { it[0] = (it[0] + 1).toByte() }.base64Encode()
    val tamperedXml = messageXml.replace(match.groupValues[1], tampered)
    assertThrows<SecurityException> { signatureValidator.verify(tamperedXml, header, keyManager.ownCert, attachment) }
  }

  private fun messagingId(messageXml: String) = Regex("<eb:Messaging[^>]*wsu:Id=\"([^\"]*)\"").find(messageXml)!!.groupValues[1]
  private fun bodyId(messageXml: String) = Regex("<env:Body[^>]*wsu:Id=\"([^\"]*)\"").find(messageXml)!!.groupValues[1]

  private fun refused(xml: String, header: MessageHeader, attachment: ByteArray, expected: String) {
    val ex = assertThrows<SecurityException> { signatureValidator.verify(xml, header, keyManager.ownCert, attachment) }
    assertTrue(ex.message!!.contains(expected), ex.message)
  }

  @Test fun `verify refuses a second Signature element`() {
    val (messageXml, header, attachment) = signedMessage()
    val signature = Regex("<ds:Signature .*?</ds:Signature>", RegexOption.DOT_MATCHES_ALL).find(messageXml)!!.value
    refused(messageXml.replace("</wsse:Security>", "$signature</wsse:Security>"), header, attachment, "Signature")
  }

  @Test fun `verify refuses a second Messaging element in another namespace`() {
    val (messageXml, header, attachment) = signedMessage()
    val extra = """<o:Messaging xmlns:o="urn:other"/>"""
    refused(messageXml.replace("<wsse:Security", "$extra<wsse:Security"), header, attachment, "exactly one Messaging")
  }

  @Test fun `verify refuses a second UserMessage element`() {
    val (messageXml, header, attachment) = signedMessage()
    val extra = """<o:UserMessage xmlns:o="urn:other"/>"""
    refused(messageXml.replace("<wsse:Security", "$extra<wsse:Security"), header, attachment, "exactly one UserMessage")
  }

  @Test fun `verify refuses a Messaging that is not a child of the header`() {
    val (messageXml, header, attachment) = signedMessage()
    val start = messageXml.indexOf("<eb:Messaging")
    val end = messageXml.indexOf("</eb:Messaging>") + "</eb:Messaging>".length
    val moved = messageXml.substring(0, start) + messageXml.substring(end)
    val inBody = moved.replace(Regex("(<env:Body[^>]*>)"), "$1" + messageXml.substring(start, end))
    refused(inBody, header, attachment, "child of env:Header")
  }

  @Test fun `verify refuses a Signature that is not under wsse Security`() {
    val (messageXml, header, attachment) = signedMessage()
    val signature = Regex("<ds:Signature .*?</ds:Signature>", RegexOption.DOT_MATCHES_ALL).find(messageXml)!!.value
    val without = messageXml.replace(signature, "")
    val bodyOpenEnd = without.indexOf(">", without.indexOf("<env:Body")) + 1
    val standalone = signature.replaceFirst("<ds:Signature ", "<ds:Signature xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" ")
    val moved = without.substring(0, bodyOpenEnd) + standalone + without.substring(bodyOpenEnd)
    refused(moved, header, attachment, "child of wsse:Security")
  }

  @Test fun `verify refuses a repeated wsu Id before validating`() {
    val (messageXml, header, attachment) = signedMessage()
    refused(messageXml.replace(bodyId(messageXml), messagingId(messageXml)), header, attachment, "Duplicate wsu:Id")
  }

  @Test fun `verify refuses a signature that does not reference the Messaging element`() {
    val (messageXml, header, attachment) = signedMessage()
    refused(messageXml.replace("URI=\"#${messagingId(messageXml)}\"", "URI=\"#${bodyId(messageXml)}\""), header, attachment, "eb:Messaging element is not covered")
  }

  @Test fun `verify refuses a signature that does not reference the attachment`() {
    val (messageXml, header, attachment) = signedMessage()
    refused(messageXml.replace("<ds:Reference URI=\"cid:message\"", "<ds:Reference URI=\"#${bodyId(messageXml)}\""), header, attachment, "attachment is not covered")
  }
}
