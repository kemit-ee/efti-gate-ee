package edelivery

import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.TimeoutException
import javax.xml.crypto.MarshalException
import javax.xml.crypto.dsig.XMLSignatureException
import javax.xml.stream.XMLStreamException

/**
 * What a peer is told when we reject its message: a bucket and a fixed text, never the exception message,
 * stack trace or class name. The details stay in our own logs.
 */
enum class FaultReason(val ebmsCode: String, val category: String, val shortDescription: String, val detail: String) {
  MALFORMED_MESSAGE("EBMS:0003", "CONTENT", "ValueInconsistent", "Malformed message"),
  INVALID_HEADER("EBMS:0003", "CONTENT", "ValueInconsistent", "Invalid message header"),
  UNSUPPORTED_MESSAGE("EBMS:0002", "CONTENT", "FeatureNotSupported", "Unsupported message"),
  AUTHENTICATION_FAILED("EBMS:0101", "PROCESSING", "FailedAuthentication", "Message authentication failed"),
  DECRYPTION_FAILED("EBMS:0102", "PROCESSING", "FailedDecryption", "Message decryption failed"),
  UNAVAILABLE("EBMS:0005", "COMMUNICATION", "ConnectionFailure", "Temporarily unable to process the message"),
  OTHER("EBMS:0004", "CONTENT", "Other", "Failed to process eDelivery message");

  companion object {
    /** Unknown exception classes fall back to [OTHER]; the unit test lists every class thrown on the msh path. */
    fun of(ex: Throwable): FaultReason = when (ex) {
      is SecurityException, is XMLSignatureException, is MarshalException -> AUTHENTICATION_FAILED
      is GeneralSecurityException -> DECRYPTION_FAILED
      is UnsupportedOperationException -> UNSUPPORTED_MESSAGE
      is NoSuchElementException, is ClassCastException, is XMLStreamException -> MALFORMED_MESSAGE
      is IllegalArgumentException -> INVALID_HEADER
      is IOException, is TimeoutException -> UNAVAILABLE
      else -> OTHER
    }
  }
}
