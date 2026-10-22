import klite.Config
import klite.debug
import klite.http.HttpException
import klite.logger
import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val ruuterTimeout: Duration = Config.optional("RUUTER_TIMEOUT_SECONDS", "90").toLong().seconds
val resqlTimeout: Duration = Config.optional("RESQL_TIMEOUT_SECONDS", "30").toLong().seconds
val registryTimeout: Duration = Config.optional("REGISTRY_TIMEOUT_SECONDS", "10").toLong().seconds

private val retryLog = logger("HttpRetry")

/**
 * Runs [action] and retries it on a transient network failure, at most [retries] times with an
 * exponential back-off starting at [backoffMs]. Only for idempotent calls: a timeout is not retried
 * (the budget is already spent), and neither is any non-IO failure such as an HTTP error status.
 */
fun <T> retryIdempotent(name: String, retries: Int = 2, backoffMs: Long = 100, action: () -> T): T {
  var attempt = 0
  while (true) {
    try {
      return action()
    } catch (e: HttpTimeoutException) {
      throw e
    } catch (e: HttpException) {
      throw e
    } catch (e: IOException) {
      if (attempt >= retries) throw e
      val wait = backoffMs shl attempt
      retryLog.debug("$name failed with ${e.javaClass.simpleName}, retry ${attempt + 1}/$retries in $wait ms")
      Thread.sleep(wait)
      attempt++
    }
  }
}
