package edelivery

import io.mockk.every
import io.mockk.mockk
import klite.Config
import klite.xml.XmlParser
import org.junit.jupiter.api.Test
import java.io.File
import java.io.StringReader
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

class EDeliveryMessageGeneratorTest {
  init {
    Config["KEYSTORE_DIR"] = "../gate/certs"
  }

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
    listOf("soap.xsd", "ebms-header.xsd").map { StreamSource(File("../xsd/edelivery/$it")) }.toTypedArray()
  ).newValidator()

  @Test fun requestMessage() {
    val req = generator.requestMessage(UserMessageParams(RequestKey(partyId, "req-id-123")), "<payload/>")
    validateXsd(req.first)
  }

  @Test fun responseMessage() {
    val req = generator.requestMessage(UserMessageParams(RequestKey(partyId, "req-id-123")), "<payload/>")
    val header = xmlParser.parse<MessageHeader>(req.first)
    val res = generator.responseMessage(header)
    validateXsd(res)
  }

  private fun validateXsd(xml: String) {
    xsdValidator.validate(StreamSource(StringReader(xml)))
  }
}
