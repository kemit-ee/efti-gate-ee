package edelivery

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import resql.ResqlClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Two providers sharing one fake ResqlClient simulate two edelivery nodes sharing one database. */
class DbAsyncResponseProviderTest {
  private val stored = ConcurrentHashMap<String, String>()
  private val claimed = ConcurrentHashMap.newKeySet<String>()
  private val resql = mockk<ResqlClient> {
    every { insertAsyncResponse(any(), any()) } answers { stored[firstArg()] = secondArg() }
    every { claimAsyncResponse(any()) } answers {
      val key = firstArg<String>()
      stored[key]?.takeIf { claimed.add(key) }
    }
  }
  private fun node(timeout: kotlin.time.Duration = 5.seconds) = DbAsyncResponseProvider(resql, timeout, 5.milliseconds, 20.milliseconds)
  private val nodeA = node()
  private val nodeB = node()
  private val key = RequestKey(PartyId("EU-PEER"))

  @Test fun `same-node response never touches the database`() {
    nodeA.register(key)
    assertTrue(nodeA.provideResponse(key, "<ok/>"))
    assertEquals("<ok/>", nodeA.waitForResponse(key))
    verify(exactly = 0) { resql.insertAsyncResponse(any(), any()) }
  }

  @Test fun `response received by another node is delivered through the database`() {
    nodeA.register(key)
    val result = java.util.concurrent.atomic.AtomicReference<String>()
    val waiter = thread { result.set(nodeA.waitForResponse(key)) }

    Thread.sleep(50)
    assertTrue(nodeB.provideResponse(key, "<multi>\n<line/>\n</multi>"))
    waiter.join(3000)

    assertEquals("<multi>\n<line/>\n</multi>", result.get())
  }

  @Test fun `response stored before the waiter first polls is still found`() {
    nodeA.register(key)
    nodeB.provideResponse(key, "<early/>")
    assertEquals("<early/>", nodeA.waitForResponse(key))
  }

  @Test fun `concurrent requests to the same peer are matched by exact key`() {
    val key2 = RequestKey(PartyId("EU-PEER"))
    nodeA.register(key); nodeA.register(key2)
    nodeB.provideResponse(key2, "two")
    nodeB.provideResponse(key, "one")

    assertEquals("one", nodeA.waitForResponse(key))
    assertEquals("two", nodeA.waitForResponse(key2))
  }

  @Test fun `a response can only be claimed once`() {
    nodeB.provideResponse(key, "<once/>")
    assertEquals("<once/>", resql.claimAsyncResponse(key.toString()))
    assertEquals(null, resql.claimAsyncResponse(key.toString()))
  }

  @Test fun `times out when no response ever arrives`() {
    val impatient = node(100.milliseconds)
    impatient.register(key)
    assertThrows(TimeoutException::class.java) { impatient.waitForResponse(key) }
  }

  @Test fun `database errors while polling do not abort the wait`() {
    var calls = 0
    every { resql.claimAsyncResponse(any()) } answers { if (calls++ < 2) error("resql down") else "<late/>" }
    nodeA.register(key)
    assertEquals("<late/>", nodeA.waitForResponse(key))
  }
}
