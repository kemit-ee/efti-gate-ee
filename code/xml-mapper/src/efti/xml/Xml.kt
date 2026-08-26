package efti.xml

import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField.*
import kotlin.text.RegexOption.DOT_MATCHES_ALL

fun String.dropXmlHeader() = substringAfter("?>").trim()
fun String.dropXmlRoot() = dropXmlHeader().substringAfter(">").substringBeforeLast("<").trim()

private val nsRegex = "(</?)([^:>\\s]+):".toRegex()

fun String.extractXmlTag(tagName: String, preserveNs: Set<String> = emptySet()): String {
  val nsPrefixes = preserveNs.associateBy { """xmlns:([^=]+?)="$it"""".toRegex().find(this)?.groups?.get(1)?.value }
  val tagRegex = "<([^:>]+:|)$tagName(?:\\s[^>]*)?>.*?</([^:>]+:|)$tagName>".toRegex(DOT_MATCHES_ALL)
  val inner = tagRegex.find(this)?.value!!
  val stripped = nsRegex.replace(inner) {
    val prefix = it.groups[2]!!.value
    if (prefix in nsPrefixes) it.value else it.groups[1]!!.value
  }
  val i = stripped.indexOf('>')
  return stripped.substring(0, i) +
    (if (nsPrefixes.isEmpty()) "" else " ") +
    nsPrefixes.entries.joinToString(separator = " ") { (prefix, uri) -> """xmlns:$prefix="$uri"""" } +
    stripped.substring(i)
}

private fun formatter(s: String) = DateTimeFormatterBuilder().appendPattern(s)
  .parseDefaulting(HOUR_OF_DAY, 0)
  .parseDefaulting(MINUTE_OF_HOUR, 0)
  .parseDefaulting(SECOND_OF_MINUTE, 0)
  .toFormatter()

val edifactDateTimeFormats = mapOf(
  "102" to formatter("yyyyMMdd").withZone(UTC),         // LocalDate (CCYYMMDD)
  "203" to formatter("yyyyMMddHHmm").withZone(UTC),     // LocalDateTime (CCYYMMDDHHMM)
  "204" to formatter("yyyyMMddHHmmss").withZone(UTC),   // LocalDateTime (CCYYMMDDHHMMSS)
  "205" to formatter("yyyyMMddHHmmXX").withZone(UTC),   // OffsetDateTime / Instant (CCYYMMDDHHMMZHHMM)
  "207" to formatter("yyyyMMddHHmmssXX").withZone(UTC), // OffsetDateTime / Instant (CCYYMMDDHHMMSSZHHMM)
)
