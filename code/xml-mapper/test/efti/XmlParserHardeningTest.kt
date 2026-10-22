package efti

import efti.xml.fti.FTI004UploadIdentifierRequest
import efti.xml.fti.xmlParser
import java.io.File
import javax.xml.stream.XMLStreamException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * xml-mapper's parser reads peer-authored business XML after edelivery verified the signature (perimeter trust model,
 * path 6): a signature proves the signer, not that the XML is safe. These tests pin what the audit sheet claims: DTDs are
 * not processed (klite's secure StAX factory, `supportDTD=false`), so an entity reference is undeclared and refused and
 * nothing is expanded. A document with a DOCTYPE but no reference still parses. A library upgrade that loosens this fails here.
 */
class XmlParserHardeningTest {
  private val sample = File("xsd/FTI004/sample.xml").readText()
  private val leaf = Regex("(?<=>)[A-Za-z0-9-]{3,}(?=</)")

  private fun withDoctype(entities: String, reference: String? = null): String {
    val body = reference?.let { ref -> sample.replaceFirst(">${leaf.find(sample.substringAfter("?>"))!!.value}</", ">$ref</") } ?: sample
    val declaration = Regex("^\\s*<\\?xml[^>]*\\?>").find(body)?.value.orEmpty()
    return declaration + "<!DOCTYPE x [$entities]>" + body.removePrefix(declaration)
  }

  private fun parse(xml: String) = xmlParser.parse<FTI004UploadIdentifierRequest>(xml)

  private fun causes(t: Throwable) = generateSequence(t) { it.cause }

  @Test fun `the unmodified sample parses, so the refusals below are about the entity`() {
    assertContains(parse(sample).toString(), "17022113-89b5-11f1-bec0-3c9c0f2eb459")
  }

  @Test fun `an external entity reference is refused by the stream reader and its content never reaches a value`() {
    val secret = File.createTempFile("xxe", ".txt").apply { writeText("TOP-SECRET-XXE-CONTENT"); deleteOnExit() }

    val failure = assertFails { parse(withDoctype("""<!ENTITY xxe SYSTEM "file://${secret.absolutePath}">""", "&xxe;")) }

    assertTrue(causes(failure).any { it is XMLStreamException }, failure.toString())
    assertFalse(causes(failure).any { it.message.orEmpty().contains("TOP-SECRET-XXE-CONTENT") })
  }

  @Test fun `an internal entity reference is refused too, so an entity bomb has nothing to multiply`() {
    val failure = assertFails { parse(withDoctype("""<!ENTITY a "EXPANDED-ENTITY-TEXT">""", "&a;")) }

    assertTrue(causes(failure).any { it is XMLStreamException }, failure.toString())
  }

  @Test fun `a DOCTYPE with declarations but no reference parses to the same result and expands nothing`() {
    val bomb = """<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;"><!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">"""

    assertEquals(parse(sample).toString(), parse(withDoctype(bomb)).toString())
  }
}
