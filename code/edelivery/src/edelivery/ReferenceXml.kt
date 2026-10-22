package edelivery

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * The `reference_xml_b64` of a verify answer (SPEC 4.1): the received `ds:Reference` element exactly as the peer wrote
 * it (algorithms, transform parameters such as `InclusiveNamespaces PrefixList`, whitespace), made self-contained by
 * Turvis. A non-repudiation receipt repeats it byte for byte, so nothing about it is rebuilt from separate fields.
 *
 * edelivery parses no XML here, so [decode] is a cheap shape check, not a validation: one UTF-8 text that is exactly one
 * `Reference` element, with no comment, processing instruction, DOCTYPE or CDATA, that declares the xmldsig namespace on
 * its own start tag and carries the `URI` it is reported for. That keeps a mangled, misplaced or wrong-namespace answer
 * out of the envelope. Well-formedness is NOT checked here: it rests on SPEC 4.2 "Turvis parses ... namespace-aware",
 * i.e. Turvis's `sign` refuses a receipt that is unbalanced, has an unbound prefix or an undefined entity (`XML_PARSE_FAIL`).
 * The mock does not do that, so it is part of what #308 has to prove against the real service.
 */
internal object ReferenceXml {
  const val MAX_BYTES = 64 * 1024
  private val START = Regex("<(?:([A-Za-z_][\\w.-]*):)?Reference(?=[\\s>/])")
  private val END = Regex("</(?:([A-Za-z_][\\w.-]*):)?Reference>")

  private const val DSIG_NS = "http://www.w3.org/2000/09/xmldsig#"
  private val ATTR = Regex("""\s+([A-Za-z_][\w.:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
  private val TAG_END = Regex("""\s*>""")

  /**
   * The start tag's attributes, read one by one, so a value is never searched for another attribute (an `Id` value that
   * contains ` xmlns:ds="..."` or ` URI="..."` must not count). Null if the tag is not attributes followed by `>`, or an
   * attribute repeats.
   */
  private fun startTagAttributes(xml: String, nameEnd: Int): Map<String, String>? {
    val out = LinkedHashMap<String, String>()
    var i = nameEnd
    while (true) {
      val m = ATTR.matchAt(xml, i) ?: break
      if (out.put(m.groupValues[1], m.groups[2]?.value ?: m.groups[3]!!.value) != null) return null
      i = m.range.last + 1
    }
    return if (TAG_END.matchAt(xml, i) != null) out else null
  }

  private fun unescapeAttribute(v: String) = v.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

  fun decode(b64: String, expectedUri: String): String {
    val bytes = try { Base64.getDecoder().decode(b64) } catch (e: IllegalArgumentException) { throw TurvisProtocolException("reference_xml_b64 is not base64") }
    if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw TurvisProtocolException("reference_xml_b64 is empty or over $MAX_BYTES bytes")
    val xml = try {
      Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) { throw TurvisProtocolException("reference_xml_b64 is not UTF-8") }
    if (xml.any { it.isISOControl() && it != '\t' && it != '\n' && it != '\r' }) throw TurvisProtocolException("reference_xml_b64 has control characters")
    if ("<?" in xml || "<!" in xml || "]]>" in xml) throw TurvisProtocolException("reference_xml_b64 has a comment, processing instruction, DOCTYPE or CDATA")
    val starts = START.findAll(xml).toList()
    val ends = END.findAll(xml).toList()
    val ok = starts.size == 1 && ends.size == 1 && starts[0].range.first == 0 && ends[0].range.last == xml.length - 1 &&
      starts[0].groupValues[1] == ends[0].groupValues[1]
    if (!ok) throw TurvisProtocolException("reference_xml_b64 is not exactly one ds:Reference element")
    val prefix = starts[0].groupValues[1]
    val attributes = startTagAttributes(xml, starts[0].range.last + 1) ?: throw TurvisProtocolException("reference_xml_b64 start tag is not plain attributes")
    if (attributes[if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"] != DSIG_NS)
      throw TurvisProtocolException("reference_xml_b64 does not declare the xmldsig namespace on its element")
    if (attributes["URI"]?.let(::unescapeAttribute) != expectedUri) throw TurvisProtocolException("reference_xml_b64 is not the reference it is reported for")
    return xml
  }
}
