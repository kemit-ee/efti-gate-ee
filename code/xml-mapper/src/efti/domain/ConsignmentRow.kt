package efti.domain

import efti.subsets.CountryCode
import efti.xml.fti.UniqueIDSetUniqueIDSet
import efti.xml.fti.extractUniqueIDSetUniqueIDSet
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
) {
  constructor(content: UniqueIDSetUniqueIDSet, xml: String): this(
    content.uil.datasetId, content.uil.platformId, content.uil.gateId,
    xml.extractUniqueIDSetUniqueIDSet(),
    content.criteria!!.transportMode,
    content.criteria.acceptanceDate?.instant,
    content.criteria.acceptanceCountry,
    content.criteria.deliveryDate?.instant,
    content.criteria.deliveryCountry,
    content.criteria.dangerousGoods,
    content.criteria.mainTransportId,
    content.criteria.mainTransportType,
    content.criteria.transportRegCountry,
    content.criteria.loadingDate?.instant,
    content.criteria.loadingCountry,
    content.criteria.unloadingDate?.instant,
    content.criteria.unloadingCountry,
    content.criteria.usedEquipmentIds,
    content.criteria.usedEquipmentCategories,
    content.criteria.usedEquipmentCountries,
    content.criteria.usedEquipmentSeq,
    content.criteria.carriedEquipmentIds,
    content.criteria.carriedEquipmentCategories,
    content.criteria.carriedEquipmentSeq,
  )
}
