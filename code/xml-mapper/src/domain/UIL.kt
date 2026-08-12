package domain

import java.util.*

typealias PlatformId = String
typealias GateId = String

data class UIL(
  val platformId: PlatformId,
  val datasetId: UUID,
  val gateId: GateId,
) {
  override fun toString() = "$gateId/$platformId/$datasetId"
}
