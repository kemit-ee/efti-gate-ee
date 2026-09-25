package edelivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds

class SingleNodeAsyncResponseProviderTest {
  private val provider = SingleNodeAsyncResponseProvider()
  private val key = RequestKey(PartyId("EU-EE"))

  @Test fun `provideResponse before register is a no-op`() {
    assertFalse(provider.provideResponse(key, "payload"))
  }

  @Test fun `provideResponse after register delivers the payload to waitForResponse`() {
    provider.register(key)
    assertTrue(provider.provideResponse(key, "payload"))
    assertEquals("payload", provider.waitForResponse(key))
  }

  @Test fun `receive throws TimeoutException when nothing arrives in time`() {
    val queue = ArrayBlockingQueue<String>(1)
    assertThrows(TimeoutException::class.java) { queue.receive(1.milliseconds) }
  }
}
