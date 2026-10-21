package edelivery

import ch.tutteli.atrium.api.fluent.en_GB.*
import ch.tutteli.atrium.api.verbs.expect
import org.junit.jupiter.api.Test

class MimeSplitterTest {
  private val crlf = "\r\n"

  private fun body(boundary: String, vararg parts: Pair<String, ByteArray>, preamble: String = "", epilogue: String = ""): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(preamble.toByteArray())
    parts.forEach { (headers, bytes) ->
      out.write("--$boundary$crlf$headers$crlf$crlf".toByteArray())
      out.write(bytes)
      out.write(crlf.toByteArray())
    }
    out.write("--$boundary--$crlf$epilogue".toByteArray())
    return out.toByteArray()
  }

  @Test fun `splits two parts and keeps the bytes exactly`() {
    val soap = "<s:Envelope>\r\n  <a>é</a>\n</s:Envelope>".toByteArray()
    val binary = ByteArray(256) { it.toByte() }

    val parts = MimeSplitter.split(body("b1", "Content-Type: application/soap+xml" to soap, "Content-ID: <message>" to binary))

    expect(parts.size).toEqual(2)
    expect(parts[0].bytes.toList()).toEqual(soap.toList())
    expect(parts[1].bytes.toList()).toEqual(binary.toList())
    expect(parts[1].contentId).toEqual("message")
    expect(parts[0].headers["content-type"]).toEqual("application/soap+xml")
  }

  @Test fun `binary data containing CRLF and near-miss dashes is kept as data`() {
    val tricky = "x\r\n-- b1\r\n-b1\r\nrest\r".toByteArray()

    val parts = MimeSplitter.split(body("b1", "X: 1" to "a".toByteArray(), "X: 2" to tricky))

    expect(parts.size).toEqual(2)
    expect(parts[1].bytes.toList()).toEqual(tricky.toList())
  }

  @Test fun `data line that merely starts with the delimiter is rejected, not guessed`() {
    expect { MimeSplitter.split(body("b1", "X: 1" to "a\r\n--b1x\r\nb".toByteArray())) }.toThrow<IllegalArgumentException>()
  }

  @Test fun `empty attachment part is allowed`() {
    val parts = MimeSplitter.split(body("b1", "X: 1" to "a".toByteArray(), "Content-ID: <message>" to ByteArray(0)))

    expect(parts.size).toEqual(2)
    expect(parts[1].bytes.size).toEqual(0)
  }

  @Test fun `epilogue after the closing delimiter is ignored`() {
    val parts = MimeSplitter.split(body("b1", "X: 1" to "a".toByteArray(), epilogue = "ignored"))

    expect(parts.size).toEqual(1)
  }

  @Test fun `part headers are lower-cased and folded into a map`() {
    val parts = MimeSplitter.split(body("b1", "Content-Type: x/y\r\nContent-Transfer-Encoding: binary" to "a".toByteArray()))

    expect(parts[0].headers).toEqual(mapOf("content-type" to "x/y", "content-transfer-encoding" to "binary"))
  }

  @Test fun `part without headers starts at the blank line`() {
    val raw = "--b1\r\n\r\nplain\r\n--b1--\r\n".toByteArray()

    val parts = MimeSplitter.split(raw)

    expect(parts.single().headers).toEqual(emptyMap())
    expect(String(parts.single().bytes)).toEqual("plain")
  }

  @Test fun `boundary comes from the body, not from a header`() {
    val parts = MimeSplitter.split(body("----=_Part_0_123.456", "X: 1" to "a".toByteArray(), "X: 2" to "b".toByteArray()))

    expect(parts.size).toEqual(2)
  }

  @Test fun `body that does not start with a boundary line is rejected`() {
    expect { MimeSplitter.split("hello".toByteArray()) }.toThrow<IllegalArgumentException>()
    expect { MimeSplitter.split(ByteArray(0)) }.toThrow<IllegalArgumentException>()
    expect { MimeSplitter.split("--b1".toByteArray()) }.toThrow<IllegalArgumentException>()
  }

  @Test fun `empty lines before the first delimiter are tolerated like the legacy parser`() {
    val parts = MimeSplitter.split("\r\n\r\n".toByteArray() + body("b1", "X: 1" to "a".toByteArray(), "X: 2" to "b".toByteArray()))

    expect(parts.size).toEqual(2)
    expect(String(parts[1].bytes)).toEqual("b")
  }

  @Test fun `preamble before the first boundary is rejected`() {
    expect { MimeSplitter.split(body("b1", "X: 1" to "a".toByteArray(), preamble = "junk\r\n")) }.toThrow<IllegalArgumentException>()
  }

  @Test fun `body without a closing delimiter is rejected`() {
    val truncated = "--b1\r\nX: 1\r\n\r\ndata that never ends".toByteArray()

    expect { MimeSplitter.split(truncated) }.toThrow<IllegalArgumentException>()
  }

  @Test fun `body cut right after a boundary is rejected`() {
    expect { MimeSplitter.split("--b1\r\nX: 1\r\n\r\na\r\n--b1".toByteArray()) }.toThrow<IllegalArgumentException>()
  }

  @Test fun `closing delimiter without any part is rejected`() {
    expect { MimeSplitter.split("--b1\r\n--b1--\r\n".toByteArray()) }.toThrow<IllegalArgumentException>()
  }

  @Test fun `part headers without a blank line are rejected`() {
    expect { MimeSplitter.split("--b1\r\nX: 1\r\n--b1--\r\n".toByteArray()) }.toThrow<IllegalArgumentException>()
  }

  @Test fun `bare LF framing is rejected, line endings are never normalised`() {
    expect { MimeSplitter.split("--b1\nX: 1\n\ndata\n--b1--\n".toByteArray()) }.toThrow<IllegalArgumentException>()
  }

  @Test fun `more than the maximum number of parts is rejected`() {
    val many = Array(MimeSplitter.MAX_PARTS + 1) { "X: $it" to "p".toByteArray() }

    expect { MimeSplitter.split(body("b1", *many)) }.toThrow<IllegalArgumentException>()
    expect(MimeSplitter.split(body("b1", *many.copyOf(MimeSplitter.MAX_PARTS).requireNoNulls())).size).toEqual(MimeSplitter.MAX_PARTS)
  }
}
