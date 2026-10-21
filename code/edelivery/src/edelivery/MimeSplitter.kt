package edelivery

/** One part of a multipart body: its headers (names lower-cased) and its bytes exactly as received. */
class MimePart(val headers: Map<String, String>, val bytes: ByteArray) {
  val contentId get() = headers["content-id"]?.trim()?.removeSurrounding("<", ">")
}

/**
 * Splits a `multipart/related` body into its parts **without decoding anything**: no charset, no XML, no
 * line-ending changes. The SOAP envelope has to reach Turvis with exactly the bytes the peer signed, and the
 * attachment is binary (SPEC docs/specs/turvis/SPEC.md 2.2 and 9.3: "edelivery's only envelope-byte handling
 * is MIME-split").
 *
 * RFC 2046 framing only: the delimiter is the first non-empty line of the body (`--boundary`), parts are separated by
 * CRLF + delimiter, the end by `--boundary--`, and a part's headers end at the first empty line. The boundary
 * is taken from the body, not from the Content-Type header (the legacy path does the same). Anything else
 * (no delimiter, no closing delimiter, bare LF line ends, too many parts) is a malformed message and throws
 * [IllegalArgumentException].
 */
object MimeSplitter {
  const val MAX_PARTS = 16
  private val CRLF = "\r\n".toByteArray()
  private val HEADER_END = "\r\n\r\n".toByteArray()

  fun split(body: ByteArray): List<MimePart> {
    var start = 0
    while (body.startsWith(CRLF, start)) start += CRLF.size // empty lines before the first delimiter, as the legacy parser tolerates
    val firstLineEnd = body.indexOf(CRLF, start)
    require(firstLineEnd > start + 2 && body[start] == '-'.code.toByte() && body[start + 1] == '-'.code.toByte()) { "Multipart body does not start with a boundary line" }
    val delimiter = body.copyOfRange(start, firstLineEnd).trimTrailingBlanks()
    val separator = CRLF + delimiter

    val parts = mutableListOf<MimePart>()
    var cursor = start + delimiter.size // just after the opening delimiter
    while (true) {
      if (body.startsWith("--".toByteArray(), cursor)) return parts.also { require(it.isNotEmpty()) { "Multipart body has no parts" } }
      val lineEnd = body.indexOf(CRLF, cursor)
      require(lineEnd >= 0) { "Multipart body is truncated after a boundary" }
      require((cursor until lineEnd).all { body[it] == ' '.code.toByte() || body[it] == '\t'.code.toByte() }) { "Multipart delimiter line has trailing text" }
      val partStart = lineEnd + CRLF.size
      val next = body.indexOf(separator, partStart - CRLF.size) // an empty part: the separator may start at the line end
      require(next >= 0) { "Multipart body has no closing boundary" }
      require(parts.size < MAX_PARTS) { "Multipart body has more than $MAX_PARTS parts" }
      parts += part(body, partStart, maxOf(partStart, next))
      cursor = next + separator.size
    }
  }

  private fun part(body: ByteArray, start: Int, end: Int): MimePart {
    // A part without headers starts with the blank line right away.
    if (body.startsWith(CRLF, start)) return MimePart(emptyMap(), body.copyOfRange(minOf(start + CRLF.size, end), end))
    val headerEnd = body.indexOf(HEADER_END, start)
    require(headerEnd in start until end) { "Multipart part has no blank line after its headers" }
    val headers = String(body, start, headerEnd - start, Charsets.ISO_8859_1).split("\r\n").mapNotNull { line ->
      line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
    }.toMap()
    return MimePart(headers, body.copyOfRange(headerEnd + HEADER_END.size, end))
  }

  private fun ByteArray.indexOf(needle: ByteArray, from: Int): Int {
    var i = maxOf(from, 0)
    outer@ while (i <= size - needle.size) {
      for (j in needle.indices) if (this[i + j] != needle[j]) { i++; continue@outer }
      return i
    }
    return -1
  }

  private fun ByteArray.startsWith(prefix: ByteArray, at: Int) =
    at >= 0 && at + prefix.size <= size && prefix.indices.all { this[at + it] == prefix[it] }

  private fun ByteArray.trimTrailingBlanks(): ByteArray {
    var end = size
    while (end > 0 && (this[end - 1] == ' '.code.toByte() || this[end - 1] == '\t'.code.toByte())) end--
    return copyOfRange(0, end)
  }
}
