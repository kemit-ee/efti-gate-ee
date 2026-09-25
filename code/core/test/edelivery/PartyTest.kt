package edelivery

import klite.Config
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartyTest {
  @Test fun `toString returns the raw value`() {
    assertEquals("EU-EE", PartyId("EU-EE").toString())
  }

  @Test fun `equality is case-insensitive`() {
    assertTrue(PartyId("EU-EE") == PartyId("eu-ee"))
    assertTrue(PartyId("EU-EE") == PartyId("Eu-Ee"))
  }

  @Test fun `different ids are not equal`() {
    assertFalse(PartyId("EU-EE") == PartyId("EU-MOCK"))
  }

  @Test fun `is not equal to a non-PartyId value`() {
    assertFalse(PartyId("EU-EE").equals("EU-EE"))
  }

  @Test fun `hashCode is case-insensitive, matching equals`() {
    assertEquals(PartyId("eu-ee").hashCode(), PartyId("EU-EE").hashCode())
  }

  @Test fun `can be used as a map key regardless of case`() {
    val map = mapOf(PartyId("EU-EE") to "gate")

    assertEquals("gate", map[PartyId("eu-ee")])
  }

  @Test fun `Config partyId reads OWN_GATE_ID`() {
    // build.gradle.kts sets -DOWN_GATE_ID=TEST for all module test tasks.
    assertEquals(PartyId("TEST"), Config.partyId)
  }
}

class EDeliveryPartyTest {
  @Test fun `implements Party with required fields`() {
    val party: Party = EDeliveryParty(PartyId("EE"), URI("https://gate.example/msh"), "cert-data")

    assertEquals(PartyId("EE"), party.id)
    assertEquals(URI("https://gate.example/msh"), party.eDeliveryUrl)
    assertEquals("cert-data", party.eDeliveryCert)
    assertEquals(null, party.tlsCert)
  }
}
