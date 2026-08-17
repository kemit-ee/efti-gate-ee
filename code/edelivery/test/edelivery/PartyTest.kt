package edelivery

import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import org.junit.jupiter.api.Test

class PartyTest {
  @Test fun `PartyId is case-insensitive`() {
    val a = PartyId("abc")
    val b = PartyId("ABC")
    val c = PartyId("def")

    expect(a == b).toEqual(true)
    expect(a.hashCode()).toEqual(b.hashCode())

    expect(a == c).toEqual(false)

    expect(a.equals(null)).toEqual(false)
    expect(a.equals("abc")).toEqual(false)
  }
}
