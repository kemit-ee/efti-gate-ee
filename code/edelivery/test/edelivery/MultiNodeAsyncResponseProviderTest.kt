package edelivery

import PubSubClient
import klite.Config
import klite.DependencyInjectingRegistry
import klite.Server
import klite.annotations.annotated
import klite.http.httpClient
import klite.json.JsonBody
import klite.json.JsonMapper
import klite.register
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pubsub.PubSubRoutes
import pubsub.TopicRegistry
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the multi-node handoff: node A registers a pending async response,
 * node B (which never saw the request) receives the inbound AS4 reply and
 * bridges it to A over the single PubSub SSE bus.
 *
 * Uses a real PubSub [Server] on an ephemeral port plus two real
 * [MultiNodeAsyncResponseProvider] instances with separate HTTP clients,
 * i.e. two simulated edelivery nodes sharing one pubsub.
 */
class MultiNodeAsyncResponseProviderTest {
  private lateinit var server: Server
  private lateinit var pubsubUrl: URI
  private lateinit var nodeA: MultiNodeAsyncResponseProvider
  private lateinit var nodeB: MultiNodeAsyncResponseProvider

  @BeforeEach fun startPubsub() {
    server = Server(InetSocketAddress(0)).apply {
      use<JsonBody>()
      register<TopicRegistry>(TopicRegistry())
      context("/api/v1") {
        annotated<PubSubRoutes>()
      }
      start(gracefulStopDelaySec = 0)
    }
    pubsubUrl = URI("http://localhost:${server.address.port}")
    nodeA = MultiNodeAsyncResponseProvider(PubSubClient(pubsubUrl, httpClient(), JsonMapper()))
    nodeB = MultiNodeAsyncResponseProvider(PubSubClient(pubsubUrl, httpClient(), JsonMapper()))
    // SSE subscriptions are established asynchronously in init; publishing
    // before both nodes are subscribed would be lost (no replay), so wait.
    Thread.sleep(2000)
  }

  @AfterEach fun stopPubsub() {
    server.stop(0)
  }

  @Test fun `resolves via dependency injection like Launcher does`() {
    Config["PUBSUB_URL"] = "http://localhost:1"
    try {
      val registry = DependencyInjectingRegistry().apply {
        register(httpClient())
        register(JsonMapper())
      }
      registry.require(MultiNodeAsyncResponseProvider::class)
    } finally {
      System.clearProperty("PUBSUB_URL")
    }
  }

  @Test fun `same-node response resolves locally`() {
    val key = RequestKey(PartyId("EE-TEST"))
    val payload = "<FTI010>local</FTI010>"

    nodeA.register(key)
    assertTrue(nodeA.provideResponse(key, payload))
    assertEquals(payload, nodeA.waitForResponse(key))
  }

  @Test fun `cross-node response is delivered via pubsub`() {
    val key = RequestKey(PartyId("EE-TEST"))
    val payload = "<FTI010>cross-node-ok</FTI010>"

    nodeA.register(key)
    val waiter = Executors.newSingleThreadExecutor { r ->
      Thread(r, "test-waiter").apply { isDaemon = true }
    }
    try {
      val future = waiter.submit<String> { nodeA.waitForResponse(key) }
      assertTrue(nodeB.provideResponse(key, payload))
      assertEquals(payload, future.get(10, TimeUnit.SECONDS))
    } finally {
      waiter.shutdownNow()
    }
  }

  @Test fun `multiline XML survives the pubsub round-trip`() {
    val key = RequestKey(PartyId("EE-TEST"))
    val payload = "<FTI010GetCmdsResponse>\n<data>cross-node-ok</data>\n</FTI010GetCmdsResponse>"

    nodeA.register(key)
    val waiter = Executors.newSingleThreadExecutor { r ->
      Thread(r, "test-waiter").apply { isDaemon = true }
    }
    try {
      val future = waiter.submit<String> { nodeA.waitForResponse(key) }
      assertTrue(nodeB.provideResponse(key, payload))
      assertEquals(payload, future.get(10, TimeUnit.SECONDS))
    } finally {
      waiter.shutdownNow()
    }
  }
}
