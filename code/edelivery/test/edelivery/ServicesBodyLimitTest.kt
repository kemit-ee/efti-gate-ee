package edelivery

import klite.Server
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/** The HTTP cap of `/services` as `Launcher` installs it (`servicesBodyLimit`), driven through a real klite server. */
class ServicesBodyLimitTest {
  private val mib = 1024 * 1024
  private var server: Server? = null

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
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
  }

  private fun turvis(mode: String) = TurvisConfig.load(
    mapOf("OWN_GATE_ID" to "EU-EE", "TURVIS_INTEGRATION_MODE" to mode, "TURVIS_URL" to "http://turvis:8080", "TURVIS_INTERNAL_TOKEN" to "t")::get,
  )

  @Test fun `in turvis mode a body above the klite default but within the payload budget is accepted, one above the budget is refused`() {
    assumeTrue(System.getenv("BODY_LIMIT_MB") == null, "BODY_LIMIT_MB is set")
    val config = turvis("turvis")
    assertEquals(200, status(config, 11 * mib))
    server?.stop(0)
    assertEquals(413, status(config, (config.maxMshBodyBytes + 1).toInt()))
  }

  @Test fun `in legacy and shadow mode the cap stays the klite default`() {
    assumeTrue(System.getenv("BODY_LIMIT_MB") == null, "BODY_LIMIT_MB is set")
    for (mode in listOf("legacy", "shadow")) {
      assertEquals(200, status(turvis(mode), 9 * mib), mode)
      server?.stop(0)
      assertEquals(413, status(turvis(mode), 11 * mib), mode)
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
