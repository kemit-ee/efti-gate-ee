package edelivery

import klite.slf4j.KliteLogger
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The log lines written while [block] runs, one string per line (klite's logger prints to one static stream, which is swapped
 * for the duration). It fails if the logger is not the klite one, so a test can never pass on an empty capture by accident.
 */
object LogCapture {
  private val setOut = KliteLogger::class.java.getDeclaredMethod("access\$setOut\$cp", PrintStream::class.java)
  private val getOut = KliteLogger::class.java.getDeclaredMethod("access\$getOut\$cp")

  @Synchronized fun lines(block: () -> Unit): List<String> {
    val original = getOut.invoke(null) as PrintStream
    val buffer = ByteArrayOutputStream()
    val capture = PrintStream(buffer, true, Charsets.UTF_8)
    setOut.invoke(null, capture)
    try { block() } finally { setOut.invoke(null, original) }
    return buffer.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
  }
}
