package efti.domain

import klite.StringValue
import java.util.*

class PlatformId(value: String): StringValue(value)
class GateId(value: String): StringValue(value)

data class UIL(
  val platformId: PlatformId,
  val datasetId: UUID,
  val gateId: GateId,
) {
  override fun toString() = "$gateId/$platformId/$datasetId"
}
