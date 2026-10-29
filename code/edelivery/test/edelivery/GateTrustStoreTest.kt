package edelivery

import ch.tutteli.atrium.api.fluent.en_GB.*
import ch.tutteli.atrium.api.verbs.expect
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** edelivery's outbound TLS trust (#312 312-DoD-7): it must survive the removal of the legacy crypto and the AS4 keystore. */
class GateTrustStoreTest {
  @TempDir lateinit var dir: Path
  private var server: HttpsServer? = null

  @AfterEach fun stop() { server?.stop(0) }

  private fun registry(vararg parties: EDeliveryParty) = mockk<PartyRegistry> { every { list() } returns parties.toList() }
  private fun party(id: String, tlsCert: String?) = EDeliveryParty(PartyId(id), URI("https://$id.example"), "x", tlsCert)
  private fun issuers(registry: PartyRegistry) =
    GateTrustStore(registry).trustManagers().filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }

  private val jvmIssuers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
    .trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }

  /** A self-signed certificate for localhost, made by the JDK's own keytool; returns the keystore file and the certificate as PEM. */
  private fun selfSigned(): Pair<File, String> {
    val keytool = File(System.getProperty("java.home"), "bin/keytool").path
    val store = dir.resolve("server-${System.nanoTime()}.p12").toFile()
    fun run(vararg args: String) = ProcessBuilder(keytool, *args).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
      .also { check(it == 0) { "keytool ${args.first()} failed" } }
    run("-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1",
      "-validity", "2", "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "changeit")
    val pem = dir.resolve("cert-${System.nanoTime()}.pem").toFile()
    run("-exportcert", "-rfc", "-alias", "s", "-keystore", store.path, "-storepass", "changeit", "-file", pem.path)
    return store to pem.readText()
  }

  private fun serve(keystore: File): Int {
    val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "changeit".toCharArray()) } }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
    val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      httpsConfigurator = HttpsConfigurator(ctx)
      createContext("/") { it.sendResponseHeaders(200, -1); it.close() }
      start()
    }
    return server!!.address.port
  }

  private fun get(port: Int, trust: GateTrustStore) =
    HttpClient.newBuilder().sslContext(trust.sslContext()).build()
      .send(HttpRequest.newBuilder(URI("https://localhost:$port/")).build(), HttpResponse.BodyHandlers.discarding()).statusCode()

  @Test fun `the JVM default CAs are kept when no party has a tlsCert`() {
    expect(jvmIssuers.size).toBeGreaterThan(0)
    expect(issuers(registry()).toSet()).toEqual(jvmIssuers.toSet())
    expect(issuers(registry(party("de", null))).toSet()).toEqual(jvmIssuers.toSet())
  }

  @Test fun `a party tlsCert is added next to the default CAs`() {
    val (_, pem) = selfSigned()

    val result = issuers(registry(party("de", pem)))

    expect(result.toSet()).toEqual(jvmIssuers.toSet() + pem.toX509())
  }

  @Test fun `an unparseable tlsCert is skipped and does not stop the others`() {
    val (_, pem) = selfSigned()

    val lines = LogCapture.lines { expect(issuers(registry(party("bad", "garbage"), party("de", pem))).toSet()).toEqual(jvmIssuers.toSet() + pem.toX509()) }

    expect(lines.joinToString("\n")).toContain("Failed to initialize TLS cert for gate bad")
    expect(lines.joinToString("\n")).toContain("1 custom certificates for gates, 1 failed")
  }

  @Test fun `a server with a certificate of a registry party is trusted`() {
    val (keystore, pem) = selfSigned()
    val port = serve(keystore)

    expect(get(port, GateTrustStore(registry(party("de", pem))))).toEqual(200)
  }

  @Test fun `a self-signed server nobody registered is refused by the handshake`() {
    val (keystore, _) = selfSigned()
    val port = serve(keystore)

    val failure = runCatching { get(port, GateTrustStore(registry())) }.exceptionOrNull()

    expect(generateSequence(failure) { it.cause }.any { it is SSLException }).toEqual(true)
  }

  @Test fun `a server whose certificate belongs to another party is refused`() {
    val (keystore, _) = selfSigned()
    val (_, otherPem) = selfSigned()
    val port = serve(keystore)

    val failure = runCatching { get(port, GateTrustStore(registry(party("fr", otherPem)))) }.exceptionOrNull()

    expect(generateSequence(failure) { it.cause }.any { it is SSLException }).toEqual(true)
  }
}
