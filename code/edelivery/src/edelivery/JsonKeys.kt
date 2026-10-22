package edelivery

/**
 * Finds a repeated key inside one JSON object, without building a document. The JSON mapper keeps the last of
 * two equal keys, so a Turvis answer that says `"verdict":"reject"` and later `"verdict":"accept"` would read as
 * an accept; the Turvis client refuses such a body instead (SPEC 4.0, 13.2: fail closed).
 *
 * Keys are compared after unescaping, so `"a"` and `"a"` are the same key. A key in one object never
 * collides with the same key in another object or in an array element's object. Anything that is not well-formed
 * JSON is not this function's business: the mapper rejects it.
 */
internal object JsonKeys {
  fun hasDuplicate(json: String): Boolean {
    // One entry per open container: a key set for an object, null for an array.
    val stack = ArrayDeque<MutableSet<String>?>()
    var expectKey = false
    var i = 0
    while (i < json.length) {
      when (json[i]) {
        '{' -> { stack.addLast(HashSet()); expectKey = true }
        '[' -> { stack.addLast(null); expectKey = false }
        '}', ']' -> { if (stack.isNotEmpty()) stack.removeLast(); expectKey = false }
        ',' -> expectKey = stack.lastOrNull() != null
        ':' -> expectKey = false
        '"' -> {
          val end = stringEnd(json, i)
          if (expectKey) {
            val keys = stack.lastOrNull()
            if (keys != null && !keys.add(unescape(json, i + 1, end))) return true
            expectKey = false
          }
          i = end
        }
      }
      i++
    }
    return false
  }

  /** Index of the closing quote of the string that opens at [start]; the end of the input if it never closes. */
  private fun stringEnd(s: String, start: Int): Int {
    var i = start + 1
    while (i < s.length) {
      when (s[i]) {
        '\\' -> i++
        '"' -> return i
      }
      i++
    }
    return s.length
  }

  private fun unescape(s: String, from: Int, to: Int): String {
    val out = StringBuilder()
    var i = from
    while (i < to) {
      val c = s[i]
      if (c != '\\' || i + 1 >= to) { out.append(c); i++; continue }
      when (val e = s[i + 1]) {
        'n' -> out.append('\n')
        't' -> out.append('\t')
        'r' -> out.append('\r')
        'b' -> out.append('\b')
        'f' -> out.append('\u000c')
        'u' -> {
          val code = if (i + 6 <= to) s.substring(i + 2, i + 6).toIntOrNull(16) else null
          if (code != null) { out.append(code.toChar()); i += 4 } else out.append(e)
        }
        else -> out.append(e)
      }
      i += 2
    }
    return out.toString()
  }
}
