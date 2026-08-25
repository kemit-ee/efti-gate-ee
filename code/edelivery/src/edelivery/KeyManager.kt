package edelivery

import klite.Config
import klite.base64Decode
import klite.base64Encode
import klite.info
import klite.isTest
import klite.logger
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
    // TODO pull private key with certificate .p12 file from secret vault
    if (Config.isTest) {
      val base64EncodedP12 = "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"
      load(base64EncodedP12.base64Decode().inputStream(), keyStorePassword)
    }
    else load(FileInputStream("$keyStoreDir/own.p12"), keyStorePassword)
  }

  private val partyCerts = ConcurrentHashMap<PartyId, X509Certificate>()
  private val gateCertSkis = ConcurrentHashMap<X509Certificate, String>()

  val partyId = Config.partyId
  private val ownAlias = partyId.value.takeIf { ownKeys.containsAlias(it) } ?: "poc"
  val ownPrivateKey = ownKeys.getKey(ownAlias, keyStorePassword) as PrivateKey
  val ownCert = ownKeys.getCertificate(ownAlias) as X509Certificate
  val ownCertSki = certSki(ownCert)
  val ownCertSerialNumber = ownCert.serialNumber.toString()

  init {
    log.info("partyId: $partyId, KeyIdentifier/SKI: $ownCertSki, SerialNumber: $ownCertSerialNumber")
    partyRegistry.onChange { gate -> partyCerts.remove(gate.id) }
  }

  fun receiverCert(partyId: PartyId) = partyCerts.getOrPut(partyId) {
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
