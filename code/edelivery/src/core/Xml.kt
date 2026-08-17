package core

private val whiteSpaceRegex = Regex("\\s+")
fun String.canonicalXml(): String {
  val s = trim()
  return whiteSpaceRegex.replace(s) { m ->
    val start = m.range.first
    val end = m.range.last
    val before = if (start - 1 >= 0) s[start - 1] else null
    val after = if (end + 1 < s.length) s[end + 1] else null
    if (before == '>' || after == '<') "" else " "
  }
}

fun String.dropXmlHeader() = substringAfter("?>").trim()
fun String.dropXmlRoot() = dropXmlHeader().substringAfter(">").substringBeforeLast("<").trim()
