import edelivery.EDeliveryParty
import edelivery.PartyId
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import resql.ResqlClient
import java.net.URI

class EDeliveryPartyRegistryTest {
  private val partyA = EDeliveryParty(PartyId("EU-EE"), URI("http://a/msh"), "cert-a")
  private val partyB = EDeliveryParty(PartyId("EU-MOCK"), URI("http://b/msh"), "cert-b")

  private val resqlClient = mockk<ResqlClient> {
    every { getParties() } returns mapOf(partyA.id to partyA)
  }
  private val pubSubClient = mockk<PubSubClient>(relaxed = true)

  @Test fun `loads parties from ReSql on construction`() {
    val registry = EDeliveryPartyRegistry(resqlClient, pubSubClient)

    assertEquals(listOf(partyA), registry.list())
    assertEquals(partyA, registry[partyA.id])
  }

  @Test fun `subscribes to both gate-changes and platform-changes on construction`() {
    EDeliveryPartyRegistry(resqlClient, pubSubClient)

    verifySubscribed("gate-changes")
    verifySubscribed("platform-changes")
  }

  @Test fun `reload refetches parties and notifies listeners for each current party`() {
    every { resqlClient.getParties() } returnsMany listOf(mapOf(partyA.id to partyA), mapOf(partyA.id to partyA, partyB.id to partyB))
    val registry = EDeliveryPartyRegistry(resqlClient, pubSubClient)
    val notified = mutableListOf<PartyId>()
    registry.onChange { notified.add(it.id) }

    registry.reload()

    assertEquals(setOf(partyA.id, partyB.id), notified.toSet())
    assertEquals(setOf(partyA.id, partyB.id), registry.list().map { it.id }.toSet())
  }

  @Test fun `a pubsub change event triggers reload`() {
    val consumerSlot = slot<(klite.sse.Event?) -> Unit>()
    every { pubSubClient.subscribe("gate-changes", capture(consumerSlot)) } returns Unit
    every { resqlClient.getParties() } returnsMany listOf(mapOf(partyA.id to partyA), mapOf(partyA.id to partyA, partyB.id to partyB))

    val registry = EDeliveryPartyRegistry(resqlClient, pubSubClient)
    assertEquals(setOf(partyA.id), registry.list().map { it.id }.toSet())

    consumerSlot.captured(null)

    assertEquals(setOf(partyA.id, partyB.id), registry.list().map { it.id }.toSet())
  }

  @Test fun `an unknown party id raises an error`() {
    val registry = EDeliveryPartyRegistry(resqlClient, pubSubClient)
    assertThrows(IllegalStateException::class.java) { registry[PartyId("nope")] }
  }

  @Test fun `a platform-changes event also triggers reload`() {
    val consumerSlot = slot<(klite.sse.Event?) -> Unit>()
    every { pubSubClient.subscribe("platform-changes", capture(consumerSlot)) } returns Unit
    every { resqlClient.getParties() } returnsMany listOf(mapOf(partyA.id to partyA), mapOf(partyA.id to partyA, partyB.id to partyB))

    val registry = EDeliveryPartyRegistry(resqlClient, pubSubClient)
    assertEquals(setOf(partyA.id), registry.list().map { it.id }.toSet())

    consumerSlot.captured(null)

    assertEquals(setOf(partyA.id, partyB.id), registry.list().map { it.id }.toSet())
  }

  @Test fun `parties is a plain settable property`() {
    val registry = EDeliveryPartyRegistry(resqlClient, pubSubClient)
    registry.parties = mapOf(partyB.id to partyB)
    assertEquals(mapOf(partyB.id to partyB), registry.parties)
  }

  private fun verifySubscribed(topic: String) {
    io.mockk.verify { pubSubClient.subscribe(topic, any()) }
  }
}
