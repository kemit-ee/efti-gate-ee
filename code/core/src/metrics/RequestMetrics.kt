package metrics

import klite.Decorator
import klite.Handler
import klite.HttpExchange
import klite.Metrics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Wires klite's built-in [Metrics]/`/metrics` OpenMetrics endpoint (klite-server 2.0.7+) up with
 * basic HTTP request/duration/error counters. Register one instance per [klite.Server] before the
 * first `context(...)` call so it's included in that server's default decorator chain.
 */
class RequestMetrics: Decorator {
  private val requestsByKey = ConcurrentHashMap<String, LongAdder>()
  private val errorsTotal = LongAdder()
  private val durationSumMs = LongAdder()
  private val durationCount = LongAdder()

  init {
    Metrics.register("httpRequestsTotal") { requestsByKey.mapValues { it.value.sum() } }
    Metrics.register("httpRequestErrorsTotal") { errorsTotal.sum() }
    Metrics.register("httpRequestDurationMsAvg") {
      val count = durationCount.sum()
      if (count == 0L) 0.0 else durationSumMs.sum().toDouble() / count
    }
  }

  override fun invoke(exchange: HttpExchange, handler: Handler): Any? {
    val start = System.nanoTime()
    exchange.onComplete {
      val ms = (System.nanoTime() - start) / 1_000_000
      val status = exchange.statusCode?.value ?: 0
      val statusClass = "${status / 100}xx"
      val key = "${exchange.method.name.lowercase()}_$statusClass"
      requestsByKey.getOrPut(key) { LongAdder() }.increment()
      if (status >= 400) errorsTotal.increment()
      durationSumMs.add(ms)
      durationCount.increment()
    }
    return handler(exchange)
  }
}
