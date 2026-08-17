package core

import klite.Config
import klite.base64Encode
import klite.info
import klite.logger
import java.io.File
import java.io.FileInputStream
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class KeyManager (private val partyRegistry: PartyRegistry) {
  private val log = logger()

  private val keyStoreDir = Config.optional("KEYSTORE_DIR", "certs")
  private val keyStorePassword = Config.optional("KEYSTORE_PASSWORD", "changeit").toCharArray()

  private val ownKeys = KeyStore.getInstance("pkcs12").apply {
    load(FileInputStream("$keyStoreDir/own.p12"), keyStorePassword)
  }

  private val partyCerts = ConcurrentHashMap<PartyId<*>, X509Certificate>()
  private val gateCertSkis = ConcurrentHashMap<X509Certificate, String>()

  val partyId = Config.partyId
  private val ownAlias = partyId.value.takeIf { ownKeys.containsAlias(it) } ?: "poc"
  val ownPrivateKey = ownKeys.getKey(ownAlias, keyStorePassword) as PrivateKey
  val ownCert = ownKeys.getCertificate(ownAlias) as X509Certificate
  val ownCertPem = File(keyStoreDir, "own.crt").readText()
  val ownCertSki = certSki(ownCert)
  val ownCertSerialNumber = ownCert.serialNumber.toString()

  init {
    log.info("partyId: $partyId, KeyIdentifier/SKI: $ownCertSki, SerialNumber: $ownCertSerialNumber")
    partyRegistry.onChange { gate -> partyCerts.remove(gate.id) }
  }

  fun receiverCert(partyId: PartyId<*>) = partyCerts.getOrPut(partyId) {
    partyRegistry[partyId].eDeliveryCert.toX509()
  }

  fun certSki(cert: X509Certificate): String = gateCertSkis.getOrPut(cert) {
    val ext = cert.getExtensionValue("2.5.29.14")
    val skiBytes = ext?.copyOfRange(4, ext.size) ?: sha1(cert.publicKey.bitString())
    skiBytes.base64Encode().also {
      log.info("${cert.subjectX500Principal.name} - KeyIdentifier/SKI: $it (extension: ${ext != null})")
    }
  }

  fun buildGatesTrustStore(): SSLContext {
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
    partyRegistry.list().filter { it.tlsCert != null }.forEach { gate ->
      ks.setCertificateEntry(gate.id.toString(), gate.tlsCert!!.toX509())
      numCustom++
    }

    log.info("Built TrustStore with $numDefault default and $numCustom custom certificates for gates")

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
    return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, SecureRandom()) }
  }
}

private fun sha1(data: ByteArray) = MessageDigest.getInstance("SHA-1").digest(data)

private fun PublicKey.bitString() = encoded.let { it.sliceArray(24 until it.size) } // drop RSA algId

fun String.toX509() =
  CertificateFactory.getInstance("X.509").generateCertificate(toByteArray().inputStream()) as X509Certificate
