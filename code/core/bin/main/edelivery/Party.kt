package edelivery

import klite.Config
import klite.StringValue
import java.net.URI

val Config.partyId: PartyId get() = PartyId(required("OWN_GATE_ID"))

open class PartyId(id: String): StringValue(id) {
  override fun toString() = value
  override fun equals(other: Any?) = value.equals((other as? PartyId)?.value, ignoreCase = true)
  override fun hashCode() = value.uppercase().hashCode()
}

interface OptionalParty {
  val id: PartyId
  val eDeliveryUrl: URI?
  val eDeliveryCert: String?
  val tlsCert: String?
}

interface Party: OptionalParty {
  override val eDeliveryUrl: URI
  override val eDeliveryCert: String
}

data class EDeliveryParty(
  override val id: PartyId,
  override val eDeliveryUrl: URI,
  override val eDeliveryCert: String,
  override val tlsCert: String? = null
): Party

interface PartyRegistry {
  operator fun get(id: PartyId): Party
  fun onChange(listener: (Party) -> Unit)
  fun list(): List<Party>
}

data class GateParty(
  val id: PartyId,
  val eDeliveryUrl: URI,
  val eDeliveryCert: String,
  val tlsCert: String?
)

data class PlatformParty(
  val id: PartyId,
  val baseUrl: URI,
  val eDeliveryCert: String?,
  val tlsCert: String?
)
