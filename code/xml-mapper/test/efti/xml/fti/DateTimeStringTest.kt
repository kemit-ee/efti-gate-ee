package efti.xml.fti

import ch.tutteli.atrium.api.fluent.en_GB.toBeLessThan
import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import org.junit.jupiter.api.Test
import java.time.Instant

class DateTimeStringTest {
  @Test fun now() {
    expect(DateTimeString().instant).toBeLessThan(Instant.now())
  }

  @Test fun format207() {
    expect(DateTimeString(value = "20260817150856+0100").instant).toEqual(Instant.parse("2026-08-17T14:08:56Z"))
  }

  @Test fun format205() {
    expect(DateTimeString(formatId = "205", value = "202608171508-0100").instant).toEqual(Instant.parse("2026-08-17T16:08:00Z"))
  }

  @Test fun format204() {
    expect(DateTimeString(formatId = "204", value = "20260817150812").instant).toEqual(Instant.parse("2026-08-17T15:08:12Z"))
  }

  @Test fun format203() {
    expect(DateTimeString(formatId = "203", value = "202608171508").instant).toEqual(Instant.parse("2026-08-17T15:08:00Z"))
  }

  @Test fun format102() {
    expect(DateTimeString(formatId = "102", value = "20260817").instant).toEqual(Instant.parse("2026-08-17T00:00:00Z"))
  }
}
