package efti

import efti.xml.fti.FTI004UploadIdentifierRequest
import efti.xml.fti.xmlParser
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFails
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test

/**
 * xml-mapper's parser reads peer-authored business XML after edelivery verified the signature (perimeter trust model,
 * path 6): a signature proves the signer, not that the XML is safe. These tests pin what the audit sheet claims: DTDs
 * are not supported, so an entity reference (external or internal) is refused instead of expanded. A library upgrade
 * that loosens this fails here.
 */
class XmlParserHardeningTest {
  private val sample = File("xsd/FTI004/sample.xml").readText()
  private val leaf = Regex("(?<=>)[A-Za-z0-9-]{3,}(?=</)")

  private fun withDoctype(entities: String, reference: String): String {
    val firstLeaf = leaf.find(sample.substringAfter("?>"))!!.value
    val body = sample.replaceFirst(">$firstLeaf</", ">$reference</")
    val declaration = Regex("^\\s*<\\?xml[^>]*\\?>").find(body)?.value.orEmpty()
    return declaration + "<!DOCTYPE x [$entities]>" + body.removePrefix(declaration)
  }

  private fun parse(xml: String) = xmlParser.parse<FTI004UploadIdentifierRequest>(xml)

  @Test fun `the unmodified sample parses, so the refusals below are about the entity`() {
    assertContains(parse(sample).toString(), "17022113-89b5-11f1-bec0-3c9c0f2eb459")
  }

  @Test fun `an external entity is refused and its content never reaches a mapped value`() {
    val secret = File.createTempFile("xxe", ".txt").apply { writeText("TOP-SECRET-XXE-CONTENT"); deleteOnExit() }

    val failure = assertFails { parse(withDoctype("""<!ENTITY xxe SYSTEM "file://${secret.absolutePath}">""", "&xxe;")) }

    assertFalse(failure.toString().contains("TOP-SECRET-XXE-CONTENT"))
  }

  @Test fun `an internal entity is refused too, so an entity bomb has nothing to multiply`() {
    assertFails { parse(withDoctype("""<!ENTITY a "EXPANDED-ENTITY-TEXT">""", "&a;")) }
  }
}
