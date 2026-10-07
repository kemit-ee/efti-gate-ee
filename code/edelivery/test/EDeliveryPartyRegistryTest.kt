import edelivery.EDeliveryParty
import edelivery.PartyId
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import resql.ResqlClient
import java.net.URI
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class EDeliveryPartyRegistryTest {
  private val partyA = EDeliveryParty(PartyId("EU-EE"), URI("http://a/msh"), "cert-a")
  private val partyB = EDeliveryParty(PartyId("EU-MOCK"), URI("http://b/msh"), "cert-b")

  private val resqlClient = mockk<ResqlClient> {
    every { getParties() } returns mapOf(partyA.id to partyA)
  }
  private val never = 1.hours

  @Test fun `loads parties from ReSql on construction`() {
    val registry = EDeliveryPartyRegistry(resqlClient, never)

    assertEquals(listOf(partyA), registry.list())
    assertEquals(partyA, registry[partyA.id])
  }

  @Test fun `reload refetches parties and notifies listeners for each current party`() {
    every { resqlClient.getParties() } returnsMany listOf(mapOf(partyA.id to partyA), mapOf(partyA.id to partyA, partyB.id to partyB))
    val registry = EDeliveryPartyRegistry(resqlClient, never)
    val notified = mutableListOf<PartyId>()
    registry.onChange { notified.add(it.id) }

    registry.reload()

    assertEquals(setOf(partyA.id, partyB.id), notified.toSet())
    assertEquals(setOf(partyA.id, partyB.id), registry.list().map { it.id }.toSet())
  }

  @Test fun `refreshes periodically`() {
    every { resqlClient.getParties() } returnsMany listOf(mapOf(partyA.id to partyA), mapOf(partyA.id to partyA, partyB.id to partyB))

    val registry = EDeliveryPartyRegistry(resqlClient, 20.milliseconds)

    val deadline = System.currentTimeMillis() + 2000
    while (registry.list().size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assertEquals(setOf(partyA.id, partyB.id), registry.list().map { it.id }.toSet())
  }

  @Test fun `an unknown party id raises an error`() {
    val registry = EDeliveryPartyRegistry(resqlClient, never)
    assertThrows(IllegalStateException::class.java) { registry[PartyId("nope")] }
  }

  @Test fun `parties is a plain settable property`() {
    val registry = EDeliveryPartyRegistry(resqlClient, never)
    registry.parties = mapOf(partyB.id to partyB)
    assertEquals(mapOf(partyB.id to partyB), registry.parties)
  }
}
