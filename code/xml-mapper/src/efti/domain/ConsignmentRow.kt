package efti.domain

import efti.subsets.CountryCode
import java.time.Instant
import java.util.*

data class ConsignmentRow(
  val datasetId: UUID,
  val platformId: PlatformId,
  val gateId: GateId,
  val xml: String,
  val transportMode: Mode?,
  val acceptanceDate: Instant?,
  val acceptanceCountry: CountryCode?,
  val deliveryDate: Instant?,
  val deliveryCountry: CountryCode?,
  val dangerousGoods: DangerousGoods?,
  val mainTransportId: String?,
  val mainTransportType: String?,
  val transportRegCountry: CountryCode?,
  val loadingDate: Instant?,
  val loadingCountry: CountryCode?,
  val unloadingDate: Instant?,
  val unloadingCountry: CountryCode?,
  val usedEquipmentIds: List<String>?,
  val usedEquipmentCategories: List<String>?,
  val usedEquipmentCountries: List<CountryCode>?,
  val usedEquipmentSeq: List<Int>?,
  val carriedEquipmentIds: List<String>?,
  val carriedEquipmentCategories: List<String>?,
  val carriedEquipmentSeq: List<Int>?,
  val status: String? = null,
)
