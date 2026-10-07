import klite.Config
import klite.logger
import klite.warn
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val registryRefreshInterval: Duration = Config.optional("REGISTRY_REFRESH_SECONDS", "60").toLong().seconds

/** Runs [action] every [interval] on a daemon thread; a failed run is logged and retried on the next tick. */
fun refreshPeriodically(name: String, interval: Duration = registryRefreshInterval, action: () -> Unit) {
  val log = logger(name)
  Executors.newSingleThreadScheduledExecutor { r -> Thread(r, name).apply { isDaemon = true } }
    .scheduleWithFixedDelay({
      try { action() } catch (e: Exception) { log.warn("Periodic refresh failed: ${e.message}") }
    }, interval.inWholeMilliseconds, interval.inWholeMilliseconds, MILLISECONDS)
}
