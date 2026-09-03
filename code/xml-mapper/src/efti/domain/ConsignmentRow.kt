package efti.domain

import efti.subsets.CountryCode
import efti.xml.fti.ParameterIDSetCriteria
import java.time.Instant
import java.util.*

data class ConsignmentRow(
  val datasetId: UUID,
  val platformId: PlatformId,
  val gateId: GateId,
  /** ParameterSetIDCriteria tag */
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
) {
  constructor(uil: UIL, criteria: ParameterIDSetCriteria, xml: String): this(
    uil.datasetId, uil.platformId, uil.gateId,
    xml,
    criteria.transportMode,
    criteria.acceptanceDate?.instant,
    criteria.acceptanceCountry,
    criteria.deliveryDate?.instant,
    criteria.deliveryCountry,
    criteria.dangerousGoods,
    criteria.mainTransportId,
    criteria.mainTransportType,
    criteria.transportRegCountry,
    criteria.loadingDate?.instant,
    criteria.loadingCountry,
    criteria.unloadingDate?.instant,
    criteria.unloadingCountry,
    criteria.usedEquipmentIds,
    criteria.usedEquipmentCategories,
    criteria.usedEquipmentCountries,
    criteria.usedEquipmentSeq,
    criteria.carriedEquipmentIds,
    criteria.carriedEquipmentCategories,
    criteria.carriedEquipmentSeq,
  )
}
