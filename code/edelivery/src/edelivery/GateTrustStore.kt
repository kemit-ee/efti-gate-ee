package edelivery

import klite.*
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The TLS trust of edelivery's outbound HTTPS client: the JVM's default CAs plus the `tlsCert` of every party in the registry.
 * Independent of the AS4 keystore and of any signing or encryption material, so it stays when the legacy crypto goes (#312).
 */
class GateTrustStore(private val partyRegistry: PartyRegistry) {
  private val log = logger()

  fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, trustManagers(), SecureRandom()) }

  internal fun trustManagers(): Array<TrustManager> {
    val defaultTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
      init(null as KeyStore?)
    }

    var numDefault = 0
    val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    for (tm in defaultTmf.trustManagers) {
      for (cert in (tm as? X509TrustManager)?.acceptedIssuers ?: emptyArray()) {
        ks.setCertificateEntry(cert.getSubjectX500Principal().name, cert)
        numDefault++
      }
    }

    var numCustom = 0
    var numFailed = 0
    partyRegistry.list().filter { it.tlsCert != null }.forEach { gate ->
      try {
        ks.setCertificateEntry(gate.id.toString(), gate.tlsCert!!.toX509())
        numCustom++
      } catch (e: Exception) {
        log.error("Failed to initialize TLS cert for gate ${gate.id}: ${gate.tlsCert}: ${e.message}") // pii-ok: public certificate
        numFailed++
      }
    }

    log.info("Built TrustStore with $numDefault default and $numCustom custom certificates for gates, $numFailed failed")

    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers
  }
}

fun String.toX509() =
  CertificateFactory.getInstance("X.509").generateCertificate(toByteArray().inputStream()) as X509Certificate
