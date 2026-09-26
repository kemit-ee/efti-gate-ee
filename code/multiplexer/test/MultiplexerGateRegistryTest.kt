import edelivery.EDeliveryParty
import edelivery.PartyId
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import klite.Config
import klite.sse.Event
import klite.sse.getSSE
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import resql.ResqlClient
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.assertEquals

class MultiplexerGateRegistryTest {
  init {
    Config["PUBSUB_URL"] = "http://pubsub:8084"
  }

  // -DOWN_GATE_ID=TEST is set for the test JVM (code/build.gradle.kts).
  private val ownGateId = PartyId("TEST")
  private val own = EDeliveryParty(ownGateId, URI("http://own/msh"), "own-cert")
  private val other = EDeliveryParty(PartyId("EU-OTHER"), URI("http://other/msh"), "cert")

  private val resqlClient = mockk<ResqlClient>()
  private val http = mockk<HttpClient>()

  // getSSE is an extension function (compiled to a static method, not an HttpClient
  // member), so it needs mockkStatic on the file it's compiled into (klite.sse.EventKt)
  // rather than a plain relaxed mock of HttpClient. Backed by a real blocking queue so
  // the registry's background subscriber thread blocks exactly like it would against a
  // live SSE stream, instead of hot-looping.
  private val eventQueue = LinkedBlockingQueue<Event>()

  @BeforeEach fun setup() {
    mockkStatic("klite.sse.EventKt")
    every { http.getSSE(any(), any()) } returns generateSequence { eventQueue.take() }
  }

  @AfterEach fun tearDown() {
    unmockkStatic("klite.sse.EventKt")
  }

  @Test fun `loads online gates on construction, excluding its own gate id`() {
    every { resqlClient.getGates("ONLINE") } returns mapOf(other.id to other, own.id to own)

    val registry = MultiplexerGateRegistry(resqlClient, http, "token")

    assertEquals(mapOf(other.id to other), registry.gates)
  }

  @Test fun `refetches gates when a gate-change event arrives on the SSE stream`() {
    val newGate = other.copy(id = PartyId("EU-NEW"))
    every { resqlClient.getGates("ONLINE") } returnsMany listOf(
      mapOf(other.id to other),
      mapOf(other.id to other, newGate.id to newGate),
    )

    val registry = MultiplexerGateRegistry(resqlClient, http, "token")
    assertEquals(setOf(other.id), registry.gates.keys)

    eventQueue.put(Event(name = gateChangeEvent))

    awaitGateKeys(registry, setOf(other.id, newGate.id))
  }

  private fun awaitGateKeys(registry: MultiplexerGateRegistry, expected: Set<PartyId>, timeoutMs: Long = 2000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (registry.gates.keys != expected && System.currentTimeMillis() < deadline) {
      Thread.sleep(10)
    }
    assertEquals(expected, registry.gates.keys)
  }
}
