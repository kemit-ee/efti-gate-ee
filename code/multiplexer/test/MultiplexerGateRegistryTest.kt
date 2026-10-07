import edelivery.EDeliveryParty
import edelivery.PartyId
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import resql.ResqlClient
import java.net.URI
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class MultiplexerGateRegistryTest {
  // -DOWN_GATE_ID=TEST is set for the test JVM (code/build.gradle.kts).
  private val ownGateId = PartyId("TEST")
  private val own = EDeliveryParty(ownGateId, URI("http://own/msh"), "own-cert")
  private val other = EDeliveryParty(PartyId("EU-OTHER"), URI("http://other/msh"), "cert")

  private val resqlClient = mockk<ResqlClient>()

  @Test fun `loads online gates on construction, excluding its own gate id`() {
    every { resqlClient.getGates("ONLINE") } returns mapOf(other.id to other, own.id to own)

    val registry = MultiplexerGateRegistry(resqlClient, 1.hours)

    assertEquals(mapOf(other.id to other), registry.gates)
  }

  @Test fun `periodically refetches gates`() {
    val newGate = other.copy(id = PartyId("EU-NEW"))
    every { resqlClient.getGates("ONLINE") } returnsMany listOf(
      mapOf(other.id to other),
      mapOf(other.id to other, newGate.id to newGate),
    )

    val registry = MultiplexerGateRegistry(resqlClient, 20.milliseconds)

    awaitGateKeys(registry, setOf(other.id, newGate.id))
  }

  @Test fun `keeps the previous gates when a refresh fails`() {
    every { resqlClient.getGates("ONLINE") } returnsMany listOf(mapOf(other.id to other)) andThenThrows RuntimeException("resql down")

    val registry = MultiplexerGateRegistry(resqlClient, 20.milliseconds)
    Thread.sleep(100)

    assertEquals(setOf(other.id), registry.gates.keys)
  }

  private fun awaitGateKeys(registry: MultiplexerGateRegistry, expected: Set<PartyId>, timeoutMs: Long = 2000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (registry.gates.keys != expected && System.currentTimeMillis() < deadline) {
      Thread.sleep(10)
    }
    assertEquals(expected, registry.gates.keys)
  }
}
