package edelivery

import java.util.UUID

/**
 * ebMS3 allows any ConversationId string, but the request key and the Ruuter `x-request-id` are UUIDs. The one mapping for
 * every path (legacy, shadow, turvis): only the canonical 8-4-4-4-12 hex form is read as a UUID, anything else becomes a
 * stable name-based UUID. The peer's own string is what is echoed back; the UUID is for internal correlation only.
 */
object ConversationIds {
  const val MAX_LENGTH = 255
  private val canonical = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

  /** The UUID when [id] (trimmed) has the canonical form, otherwise null. */
  fun canonicalUuid(id: String?): UUID? = id?.trim()?.takeIf { canonical.matches(it) }?.let(UUID::fromString)

  /** A missing, blank or oversize id is refused (IllegalArgumentException, answered as XML_PARSE_FAIL). */
  fun requestId(id: String?): UUID {
    require(!id.isNullOrBlank() && id.length <= MAX_LENGTH) { "Invalid ConversationId" }
    return canonicalUuid(id) ?: UUID.nameUUIDFromBytes(id.toByteArray())
  }
}
