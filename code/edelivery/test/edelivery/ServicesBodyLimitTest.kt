package edelivery

import klite.Server
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertTrue

/** The HTTP cap of `/services` as `Launcher` installs it (`servicesBodyLimit`), driven through a real klite server. */
class ServicesBodyLimitTest {
  private val mib = 1024 * 1024
  private var server: Server? = null
  private val REFUSED_BY_RESET = -1

  @AfterEach fun stop() { server?.stop(0) }

  private fun status(config: TurvisConfig, bodyBytes: Int): Int {
    val port = ServerSocket(0).use { it.localPort }
    server = Server(listen = InetSocketAddress("127.0.0.1", port)).apply {
      context("/services") {
        servicesBodyLimit(config)
        post("/msh") { requestStream.readAllBytes().size }
      }
      start()
    }
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/services/msh")).POST(HttpRequest.BodyPublishers.ofByteArray(ByteArray(bodyBytes))).build()
    // On Linux the server may close the socket after its 413 before the client has written the whole body.
    return try { HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode() } catch (e: IOException) { REFUSED_BY_RESET }
  }

  private fun assertRefused(status: Int, message: String? = null) = assertTrue(status == 413 || status == REFUSED_BY_RESET, "$message: expected 413 or a reset connection, got $status")

  /** An over-limit body is refused with 413, but the server may close the socket before the client has sent all of it. */
  private fun refused(config: TurvisConfig, bodyBytes: Int): Boolean =
    try { status(config, bodyBytes) == 413 } catch (_: java.io.IOException) { true }

  private fun turvis(mode: String) = TurvisConfig.load(
    mapOf("OWN_GATE_ID" to "EU-EE", "TURVIS_INTEGRATION_MODE" to mode, "TURVIS_URL" to "http://turvis:8080", "TURVIS_INTERNAL_TOKEN" to "t")::get,
  )

  @Test fun `in turvis mode a body above the klite default but within the payload budget is accepted, one above the budget is refused`() {
    assumeTrue(System.getenv("BODY_LIMIT_MB") == null, "BODY_LIMIT_MB is set")
    val config = turvis("turvis")
    assertEquals(200, status(config, 11 * mib))
    server?.stop(0)
    assertTrue(refused(config, (config.maxMshBodyBytes + 1).toInt()))
  }

  @Test fun `in legacy and shadow mode the cap stays the klite default`() {
    assumeTrue(System.getenv("BODY_LIMIT_MB") == null, "BODY_LIMIT_MB is set")
    for (mode in listOf("legacy", "shadow")) {
      assertEquals(200, status(turvis(mode), 9 * mib), mode)
      server?.stop(0)
      assertTrue(refused(turvis(mode), 11 * mib), mode)
      server?.stop(0)
    }
  }

  @Test fun `the Ruuter edge cap of POST services msh is never above the legacy service cap`() {
    val msh = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.map { it.resolve("DSL/Ruuter/services/POST/msh.yml") }.firstOrNull { Files.exists(it) }
    assumeTrue(msh != null, "DSL/Ruuter/services/POST/msh.yml is not in the build context")
    assumeTrue(System.getenv("BODY_LIMIT_MB") == null, "BODY_LIMIT_MB is set")
    val edge = Regex("""^\s*max_body_bytes:\s*(\d+)\s*$""", RegexOption.MULTILINE).find(Files.readString(msh!!))!!.groupValues[1].toLong()
    // The edge is a static literal, so it follows the legacy default; the turvis cutover (#308) raises it together with TURVIS_MAX_PAYLOAD_MIB.
    assertEquals(legacyPayloadLimit(), edge)
  }
}
