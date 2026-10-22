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
 * `Reference` element, with no comment, processing instruction, DOCTYPE or CDATA. That is enough to keep a mangled or
 * smuggling answer out of the envelope; well-formedness is still enforced by Turvis, whose `sign` parses the whole
 * receipt and refuses an unbalanced or unbound fragment.
 */
internal object ReferenceXml {
  const val MAX_BYTES = 64 * 1024
  private val START = Regex("<(?:([A-Za-z_][\\w.-]*):)?Reference(?=[\\s>/])")
  private val END = Regex("</(?:([A-Za-z_][\\w.-]*):)?Reference>")

  fun decode(b64: String): String {
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
    return xml
  }
}
