package edelivery

import klite.logger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

open class SingleNodeAsyncResponseProvider: AsyncResponseProvider {
  protected val pendingResponses = ConcurrentHashMap<RequestKey, BlockingQueue<String>>()
  protected val log = logger()

  override fun register(key: RequestKey) {
    pendingResponses[key] = ArrayBlockingQueue(1)
  }

  override fun waitForResponse(key: RequestKey): String {
    val queue = pendingResponses[key] ?: error("$key not registered")
    return queue.receive(eDeliveryTimeout).also { pendingResponses.remove(key) }
  }

  override fun provideResponse(key: RequestKey, payload: String): Boolean {
    val queue = pendingResponses[key] ?: return false
    queue.offer(payload)
    return true
  }
}

fun <T: Any> BlockingQueue<T>.receive(timeout: Duration): T =
  poll(timeout.inWholeSeconds, SECONDS) ?: throw TimeoutException("No data received after $timeout")
