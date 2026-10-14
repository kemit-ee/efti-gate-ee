import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class PeriodicRefreshTest {
  @Test fun `runs the action repeatedly on the configured interval`() {
    val latch = CountDownLatch(3)

    refreshPeriodically("test-refresh", 5.milliseconds) { latch.countDown() }

    assertTrue(latch.await(5, SECONDS), "expected at least 3 runs within 5s")
  }

  @Test fun `a failing run is swallowed and doesn't stop the next tick`() {
    val attempts = AtomicInteger()
    val successes = CountDownLatch(2)

    refreshPeriodically("test-refresh-failure", 5.milliseconds) {
      if (attempts.getAndIncrement() == 0) throw RuntimeException("boom")
      successes.countDown()
    }

    assertTrue(successes.await(5, SECONDS), "expected runs to continue after the first one threw")
  }
}
