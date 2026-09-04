package efti.xml

import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField.*

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
