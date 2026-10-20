package edelivery

import ch.tutteli.atrium.api.fluent.en_GB.*
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.util.concurrent.TimeoutException
import java.util.zip.ZipException
import javax.crypto.AEADBadTagException
import javax.xml.crypto.dsig.XMLSignatureException
import javax.xml.stream.XMLStreamException

class SoapFaultReasonsTest {
  private val generator = EDeliveryMessageGenerator(mockk(relaxed = true))

  @Test fun `every exception class thrown on the msh path has a bucket`() {
    val expected = mapOf<Throwable, FaultReason>(
      SecurityException("Invalid message signature") to FaultReason.AUTHENTICATION_FAILED,
      XMLSignatureException("bad") to FaultReason.AUTHENTICATION_FAILED,
      AEADBadTagException("Tag mismatch") to FaultReason.DECRYPTION_FAILED,
      InvalidAlgorithmParameterException("params") to FaultReason.DECRYPTION_FAILED,
      UnsupportedOperationException("Unsupported message 'x'") to FaultReason.UNSUPPORTED_MESSAGE,
      NoSuchElementException() to FaultReason.MALFORMED_MESSAGE,
      ClassCastException() to FaultReason.MALFORMED_MESSAGE,
      XMLStreamException("parse") to FaultReason.MALFORMED_MESSAGE,
      IllegalArgumentException("No valid KeyIdentifier") to FaultReason.INVALID_HEADER,
      ZipException("Not in GZIP format") to FaultReason.MALFORMED_MESSAGE,
      EOFException("Unexpected end of ZLIB input stream") to FaultReason.MALFORMED_MESSAGE,
      MalformedPayloadException("too large") to FaultReason.MALFORMED_MESSAGE,
      IOException("down") to FaultReason.UNAVAILABLE,
      TimeoutException() to FaultReason.UNAVAILABLE,
    )
    expected.forEach { (ex, reason) -> expect(FaultReason.of(ex)).toEqual(reason) }
  }

  @Test fun `unknown exception falls back to Other`() {
    expect(FaultReason.of(IllegalStateException("surprise"))).toEqual(FaultReason.OTHER)
    expect(FaultReason.of(GeneralSecurityException("x"))).toEqual(FaultReason.DECRYPTION_FAILED)
  }

  @Test fun `soap fault carries only the bucket, never exception details`() {
    val secret = "org.apache.santuario.SecretInternalException C:\\internal\\path cert CN=Internal"
    FaultReason.entries.forEach { reason ->
      val fault = generator.soapFault(reason)
      expect(fault).toContain(reason.ebmsCode)
      expect(fault).toContain(reason.detail)
      expect(fault).notToContain(secret)
      expect(fault).notToContain("Exception")
      expect(fault).notToContain("\tat ")
    }
  }

  @Test fun `logged exception keeps class names and frames but no messages`() {
    val ex = IllegalStateException("<driver>Jaan Tamm</driver>", XMLStreamException("<id>EE123</id>"))
    val logged = ex.withoutMessages()
    expect(logged.toString()).notToContain("Jaan Tamm")
    expect(logged.cause.toString()).notToContain("EE123")
    expect(logged.cause!!.message).toEqual(XMLStreamException::class.java.name)
    expect(logged.stackTrace.toList()).toEqual(ex.stackTrace.toList())
  }
}
