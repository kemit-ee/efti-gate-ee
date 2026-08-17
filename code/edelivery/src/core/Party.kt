package core

import klite.Config
import klite.StringValue
import klite.jdbc.BaseEntity
import java.net.URI

val Config.partyId get() = PartyId(required("OWN_PARTY_ID"))

open class PartyId<P: BaseEntity<*>>(id: String): StringValue(id) {
  override fun toString() = value
  override fun equals(other: Any?) = value.equals((other as? PartyId<*>)?.value, ignoreCase = true)
  override fun hashCode() = value.uppercase().hashCode()
}

fun PartyId(id: String) = PartyId<BaseEntity<Any>>(id)

interface OptionalParty {
  val id: PartyId<*>
  val eDeliveryUrl: URI?
  val eDeliveryCert: String?
  val tlsCert: String?
}

interface Party: OptionalParty {
  override val eDeliveryUrl: URI
  override val eDeliveryCert: String
}

data class EDeliveryParty(
  override val id: PartyId<*>,
  override val eDeliveryUrl: URI,
  override val eDeliveryCert: String,
  override val tlsCert: String? = null
): Party

interface PartyRegistry {
  operator fun get(id: PartyId<*>): Party
  fun onChange(listener: (Party) -> Unit)
  fun list(): List<Party>
}
