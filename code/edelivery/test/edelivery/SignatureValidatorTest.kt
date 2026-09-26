package edelivery

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import klite.base64Decode
import klite.base64Encode
import klite.xml.XmlParser
import org.junit.jupiter.api.Test
import java.io.File
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
}
