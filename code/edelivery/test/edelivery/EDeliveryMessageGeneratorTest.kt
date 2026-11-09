package edelivery

import io.mockk.every
import io.mockk.mockk
import klite.xml.XmlParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
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

  private val exc = "http://www.w3.org/2001/10/xml-exc-c14n#"
  private val swa = "http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Signature-Transform"

  private fun receiptFor(refs: List<MessageHeader.SignatureReference>): String {
    val header = xmlParser.parse<MessageHeader>(generator.requestMessage(UserMessageParams(RequestKey(partyId)), "<payload/>").first)
    return generator.responseMessage(header.copy(references = refs))
  }

  private fun nrSection(res: String) = res.substringAfter("<ebbp:NonRepudiationInformation").substringBeforeLast("</ebbp:NonRepudiationInformation>")

  private fun ref(i: Int, uri: String = "#ref-$i", digestMethod: String? = null, transforms: List<MessageHeader.Transform> = emptyList()) =
    MessageHeader.SignatureReference(uri, "digest$i=", digestMethod, transforms)

  @Test fun `receipt emits one NR part per reference for 1, 2 and 4 references without throwing`() {
    for (n in listOf(1, 2, 4)) {
      val res = receiptFor((0 until n).map { ref(it) })
      assertEquals(n, Regex("<ebbp:MessagePartNRInformation>").findAll(res).count(), "parts for $n references")
    }
  }

  @Test fun `parsing a real outbound header reads the Messaging reference's PrefixList and the other references' transforms`() {
    val header = xmlParser.parse<MessageHeader>(generator.requestMessage(UserMessageParams(RequestKey(partyId)), "<payload/>").first)
    assertEquals(3, header.references.size)
    assertEquals(listOf(MessageHeader.Transform(exc, "env")), header.references[0].transforms)
    assertEquals(listOf(MessageHeader.Transform(exc, null)), header.references[1].transforms)
    assertEquals(listOf(MessageHeader.Transform(swa, null)), header.references[2].transforms)
    assertTrue(header.references.all { it.digestMethod == "http://www.w3.org/2001/04/xmlenc#sha256" })
  }

  @Test fun `a signature without references is refused instead of acknowledging nothing`() {
    assertFailsWith<IllegalArgumentException> { receiptFor(emptyList()) }
  }

  @Test fun `receipt repeats the transforms and digest method of each peer reference, in the peer's order`() {
    val res = receiptFor(listOf(
      ref(0, "cid:att", "http://www.w3.org/2001/04/xmlenc#sha512", listOf(MessageHeader.Transform(swa))),
      ref(1, "#_body", null, listOf(MessageHeader.Transform(exc))),
      ref(2, "#_msg", null, listOf(MessageHeader.Transform(exc, "env other"))),
    ))
    val nr = nrSection(res)
    assertTrue(nr.indexOf("cid:att") < nr.indexOf("#_body") && nr.indexOf("#_body") < nr.indexOf("#_msg"))
    assertTrue(nr.contains("""<ds:Reference xmlns:ds="http://www.w3.org/2000/09/xmldsig#" URI="cid:att"><ds:Transforms><ds:Transform Algorithm="$swa"></ds:Transform></ds:Transforms><ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha512">"""), nr)
    assertTrue(nr.contains("""URI="#_msg"><ds:Transforms><ds:Transform Algorithm="$exc"><ec:InclusiveNamespaces xmlns:ec="$exc" PrefixList="env other"></ec:InclusiveNamespaces></ds:Transform></ds:Transforms><ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256">"""), nr)
  }

  @Test fun `receipt escapes peer supplied reference values`() {
    val res = receiptFor(listOf(ref(0, "#a\"<b>&", "x\"y", listOf(MessageHeader.Transform("t\"<", "p\"<")))))
    val nr = nrSection(res)
    for (raw in listOf("#a\"<b>", "x\"y", "t\"<", "p\"<")) assertTrue(!nr.contains(raw), "unescaped $raw in $nr")
    assertTrue(nr.contains("URI=\"#a&quot;&lt;b&gt;&amp;\""), nr)
  }

  @Test fun `a peer's non-UUID ConversationId is echoed in the message, escaped`() {
    val xml = generator.requestMessage(UserMessageParams(RequestKey(partyId), conversationId = "conv-<1>\"x"), "<payload/>").first
    assertTrue(xml.contains("<eb:ConversationId>conv-&lt;1&gt;&quot;x</eb:ConversationId>"), xml)
  }

  @Test fun `without an echoed ConversationId the request key UUID is used`() {
    val key = RequestKey(partyId)
    assertTrue(generator.requestMessage(UserMessageParams(key), "<payload/>").first.contains("<eb:ConversationId>${key.requestId}</eb:ConversationId>"))
  }

  private fun validateXsd(xml: String) {
    xsdValidator.validate(StreamSource(StringReader(xml)))
  }
}
