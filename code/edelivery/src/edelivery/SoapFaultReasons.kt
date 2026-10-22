package edelivery

import java.io.EOFException
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.TimeoutException
import java.util.zip.ZipException
import javax.xml.crypto.MarshalException
import javax.xml.crypto.dsig.XMLSignatureException
import javax.xml.stream.XMLStreamException

/**
 * What a peer is told when we reject its message: a bucket and a fixed text, never the exception message,
 * stack trace or class name. The details stay in our own logs.
 *
 * All entries but the last three use the reason codes of the Turvis contract (docs/specs/turvis/SPEC.md section 5)
 * with the ebMS fault code and short description of section 13.2a, so the peer sees the same fault before and
 * after the Turvis cutover (#305/#308). The last three have no code in the catalogue because they come from the
 * in-process path (unknown root tag, I/O trouble, anything unmapped); revisit them at the cutover.
 */
enum class FaultReason(val ebmsCode: String, val category: String, val shortDescription: String, val detail: String) {
  XML_PARSE_FAIL("EBMS:0004", "CONTENT", "InvalidHeader", "Malformed XML"),
  SIG_CRYPTO_FAIL("EBMS:0101", "PROCESSING", "SignatureFailure", "Signature invalid"),
  PAYLOAD_DECRYPT_FAIL("EBMS:0102", "PROCESSING", "DecryptionFailure", "Payload decryption failed"),
  PAYLOAD_TOO_LARGE("EBMS:0003", "CONTENT", "ValueInconsistent", "Oversize payload"),

  // The rest of the peer-facing Turvis reason codes (SPEC section 5 / 13.2a), named as the code so the peer
  // reads the same fault whichever path rejected the message. Only the Turvis path produces them.
  XML_ATTACK_DETECTED("EBMS:0004", "CONTENT", "InvalidHeader", "Parser safety limit hit"),
  SIG_MISSING("EBMS:0101", "PROCESSING", "SignatureFailure", "Required signature absent"),
  SIG_POLICY_FAIL("EBMS:0101", "PROCESSING", "SignatureFailure", "Required content not signed"),
  SIG_ALGO_UNSUPPORTED("EBMS:0101", "PROCESSING", "SignatureFailure", "Algorithm not allowed"),
  SIGNER_UNTRUSTED("EBMS:0101", "PROCESSING", "SignatureFailure", "Signer not trusted"),
  SIGNER_IDENTITY_MISMATCH("EBMS:0101", "PROCESSING", "SignatureFailure", "Signer identity differs from the sender"),
  WRAPPED_KEY_MALFORMED("EBMS:0102", "PROCESSING", "DecryptionFailure", "Wrapped key malformed"),
  SKI_MISMATCH("EBMS:0102", "PROCESSING", "DecryptionFailure", "Message not encrypted for this gate"),
  AES_UNWRAP_FAIL("EBMS:0102", "PROCESSING", "DecryptionFailure", "Key unwrap failed"),
  ALGO_UNSUPPORTED("EBMS:0003", "CONTENT", "ValueInconsistent", "Algorithm not allowed"),
  // In-process path only, no Turvis code.
  UNSUPPORTED_MESSAGE("EBMS:0002", "CONTENT", "FeatureNotSupported", "Unsupported message"),
  UNAVAILABLE("EBMS:0005", "COMMUNICATION", "ConnectionFailure", "Temporarily unable to process the message"),
  OTHER("EBMS:0004", "CONTENT", "Other", "Failed to process eDelivery message");

  companion object {
    /** Unknown exception classes fall back to [OTHER]; the unit test lists every class thrown on the msh path. */
    /**
     * The fault of a Turvis reason, or null when the contract answers HTTP 503 without an ebMS fault
     * (internal, transient, outbound-only, programmer-error reasons; SPEC 13.2).
     */
    fun ofTurvis(reason: TurvisReason): FaultReason? = reason.takeIf { it.answeredWithFault }?.let { r -> entries.firstOrNull { it.name == r.name } }

    fun of(ex: Throwable): FaultReason = when (ex) {
      is TurvisRejectException -> ofTurvis(ex.rejection.effectiveReason) ?: UNAVAILABLE
      is PayloadTooLargeException -> PAYLOAD_TOO_LARGE
      is ZipException, is EOFException -> XML_PARSE_FAIL
      is SecurityException, is XMLSignatureException, is MarshalException -> SIG_CRYPTO_FAIL
      is GeneralSecurityException -> PAYLOAD_DECRYPT_FAIL
      is UnsupportedOperationException -> UNSUPPORTED_MESSAGE
      is NoSuchElementException, is ClassCastException, is XMLStreamException, is IllegalArgumentException -> XML_PARSE_FAIL
      is IOException, is TimeoutException -> UNAVAILABLE
      else -> OTHER
    }
  }
}

/** The multipart body has too few parts or a part of the wrong type; answered with 400 and the [FaultReason.XML_PARSE_FAIL] fault. */
class MalformedMultipartException : IllegalArgumentException("Malformed multipart message")

/** A decompressed payload over the size limit; ordered before [IllegalArgumentException] in [FaultReason.of]. */
class PayloadTooLargeException(message: String) : IllegalArgumentException(message)

/**
 * A copy of [this] for the log that keeps the class names and stack frames but none of the messages, which
 * parsers fill with fragments of the input they choked on.
 */
internal fun Throwable.withoutMessages(): Throwable =
  Throwable(javaClass.name, cause?.takeIf { it !== this }?.withoutMessages()).also { it.stackTrace = stackTrace }
