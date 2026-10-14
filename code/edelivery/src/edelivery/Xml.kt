package edelivery

import kotlin.text.RegexOption.DOT_MATCHES_ALL

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

private val nsRegex = "(</?)([^:>\\s]+):".toRegex()

fun String.extractXmlTag(tagName: String, preserveNs: Set<String> = emptySet()): String {
  val nsPrefixes = preserveNs.mapNotNull { ns -> """xmlns:([^=]+?)="${Regex.escape(ns)}"""".toRegex().find(this)?.groupValues?.get(1)?.let { it to ns } }.toMap()
  val tagRegex = "<([^:>]+:|)$tagName(?:\\s[^>]*)?>.*?</([^:>]+:|)$tagName>".toRegex(DOT_MATCHES_ALL)
  val inner = tagRegex.find(this)?.value!!
  val stripped = nsRegex.replace(inner) {
    val prefix = it.groups[2]!!.value
    if (prefix in nsPrefixes) it.value else it.groups[1]!!.value
  }
  val i = stripped.indexOf('>')
  val declarations = nsPrefixes.filterValues { it !in stripped.substring(0, i) }
  return stripped.substring(0, i) +
    declarations.entries.joinToString(separator = "") { (prefix, uri) -> """ xmlns:$prefix="$uri"""" } +
    stripped.substring(i)
}

fun String.extractIncludedNoteContent(): String {
  val note = extractXmlTag("IncludedNote")
  return """<Content [^>]*>(.*?)</Content>""".toRegex(DOT_MATCHES_ALL).find(note)?.groups?.get(1)?.value?.trim() ?: note
}
