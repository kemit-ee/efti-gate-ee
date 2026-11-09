package edelivery

import io.mockk.every
import io.mockk.mockk
import klite.xml.XmlParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.StringReader
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

class EDeliveryMessageGeneratorTest {
  val partyId = PartyId("receiver")
  val cert = javaClass.getResourceAsStream("test-cert.pem")!!.reader().use { it.readText() }
  val party = mockk<Party> {
    every { eDeliveryCert } returns cert
  }

  val partyRegistry = mockk<PartyRegistry>(relaxUnitFun = true) {
    every { this@mockk[partyId] } returns party
  }
  val keyManager = KeyManager(partyRegistry)
  val generator = EDeliveryMessageGenerator(keyManager)
  val xmlParser = XmlParser()
  val xsdValidator = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema").newSchema(
    listOf("soap.xsd", "ebms-header.xsd").map { StreamSource(File("xsd/$it")) }.toTypedArray()
  ).newValidator()

  @Test fun requestMessage() {
    val req = generator.requestMessage(UserMessageParams(RequestKey(partyId)), "<payload/>")
    validateXsd(req.first)
  }

  @Test fun responseMessage() {
    val req = generator.requestMessage(UserMessageParams(RequestKey(partyId)), "<payload/>")
    val header = xmlParser.parse<MessageHeader>(req.first)
    val res = generator.responseMessage(header)
    validateXsd(res)
  }

  @Test fun `payload key is 128 bits as the declared aes128-gcm requires`() {
    assertEquals(16, generator.generateAESKey().encoded.size)
    assertTrue("xmlenc11#aes128-gcm" in generator.requestMessage(UserMessageParams(RequestKey(partyId)), "<payload/>").first)
  }

  private fun validateXsd(xml: String) {
    xsdValidator.validate(StreamSource(StringReader(xml)))
  }
}
