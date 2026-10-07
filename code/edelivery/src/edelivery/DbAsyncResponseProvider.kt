package edelivery

import klite.info
import klite.warn
import resql.ResqlClient
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

class DbAsyncResponseProvider(
  private val resqlClient: ResqlClient,
  private val timeout: Duration = eDeliveryTimeout,
  private val minPollInterval: Duration = 50.milliseconds,
  private val maxPollInterval: Duration = 250.milliseconds,
): SingleNodeAsyncResponseProvider() {

  override fun waitForResponse(key: RequestKey): String {
    val queue = pendingResponses[key] ?: error("$key not registered")
    val deadline = System.nanoTime() + timeout.inWholeNanoseconds
    var interval = minPollInterval
    try {
      while (true) {
        queue.poll(interval.inWholeMilliseconds, MILLISECONDS)?.let { return it }
        claim(key)?.let { return it }
        val remaining = (deadline - System.nanoTime()).nanoseconds
        if (remaining <= Duration.ZERO) throw TimeoutException("No data received after $timeout")
        interval = minOf(interval * 1.5, maxPollInterval, remaining.coerceAtLeast(1.milliseconds))
      }
    } finally {
      pendingResponses.remove(key)
    }
  }

  override fun provideResponse(key: RequestKey, payload: String): Boolean {
    if (super.provideResponse(key, payload)) return true
    log.info("No local waiter for $key, storing response for another node")
    resqlClient.insertAsyncResponse(key.toString(), payload)
    return true
  }

  private fun claim(key: RequestKey): String? = try {
    resqlClient.claimAsyncResponse(key.toString())
  } catch (e: Exception) {
    log.warn("Polling stored response for $key failed: ${e.message}")
    null
  }
}
