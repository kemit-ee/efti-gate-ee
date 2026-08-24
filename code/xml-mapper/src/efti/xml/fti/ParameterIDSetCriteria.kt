package efti.xml.fti

import efti.domain.DangerousGoods
import efti.domain.Mode
import efti.domain.UIL
import efti.subsets.CountryCode
import klite.xml.XmlPath
import org.intellij.lang.annotations.Language

data class UniqueIDSetUniqueIDSet(
  @XmlPath("") val uil: UIL,
  @XmlPath("ParameterIDSetCriteria") val criteria: ParameterIDSetCriteria? = null
) {
  @Language("xml") fun render(): String {
    val criteriaXml = ParameterIDSetCriteria.render(criteria)
    return """<UniqueIDSetUniqueIDSet><GateID>${uil.gateId}</GateID><PlatformID>${uil.platformId}</PlatformID><DatasetID schemeID="RFC 9562-4">${uil.datasetId}</DatasetID>${if (criteriaXml.isNotEmpty()) "<ParameterIDSetCriteria>$criteriaXml</ParameterIDSetCriteria>" else ""}</UniqueIDSetUniqueIDSet>"""
  }
}

/** Upload data */
data class ParameterIDSetCriteria(
  @XmlPath("CarrierAcceptanceDateParameterScope/SpecifiedDateTime") val acceptanceDate: DateTimeString? = null,
  @XmlPath("CarrierAcceptanceCountryParameterScope/CountryID") val acceptanceCountry: CountryCode? = null,

  @XmlPath("DeliveryDateParameterScope/SpecifiedDateTime") val deliveryDate: DateTimeString? = null,
  @XmlPath("DeliveryCountryParameterScope/CountryID") val deliveryCountry: CountryCode? = null,

  @XmlPath("DangerousGoodsIndicationCodeParameterScope/DangerousGoodsIndicationParameterCode") val dangerousGoods: DangerousGoods? = null,

  @XmlPath("MainCarriageModeCodeParameterScope/TransportModeParameterCode") val transportMode: Mode? = null,

  @XmlPath("MainCarriageTransportMeansIDParameterScope/ID") val mainTransportId: String? = null,
  @XmlPath("MainCarriageTransportMeansTypeCodeParameterScope/TransportMeansParameterCode") val mainTransportType: String? = null,
  @XmlPath("TransportMeansRegistrationCountryParameterScope/CountryID") val transportRegCountry: CountryCode? = null,

  @XmlPath("MainCarriageLoadingDateParameterScope/SpecifiedDateTime") val loadingDate: DateTimeString? = null,
  @XmlPath("MainCarriageLoadingCountryParameterScope/CountryID") val loadingCountry: CountryCode? = null,

  @XmlPath("MainCarriageUnloadingDateParameterScope/SpecifiedDateTime") val unloadingDate: DateTimeString? = null,
  @XmlPath("MainCarriageUnloadingCountryParameterScope/CountryID") val unloadingCountry: CountryCode? = null,

  @XmlPath("UsedTransportEquipmentIDParameterScope/ID") val usedEquipmentIds: List<String> = emptyList(),
  @XmlPath("UsedTransportEquipmentCategoryCodeParameterScope/TransportEquipmentCategoryParameterCode") val usedEquipmentCategories: List<String> = emptyList(),
  @XmlPath("UsedTransportEquipmentRegistrationCountryParameterScope/CountryID") val usedEquipmentCountries: List<CountryCode> = emptyList(),
  @XmlPath("UsedTransportEquipmentSequenceNumberParameterScope/SequenceNumeric") val usedEquipmentSeq: List<Int> = emptyList(),

  @XmlPath("CarriedTransportEquipmentIDParameterScope/ID") val carriedEquipmentIds: List<String> = emptyList(),
  @XmlPath("CarriedTransportEquipmentCategoryCodeParameterScope/TransportEquipmentCategoryParameterCode") val carriedEquipmentCategories: List<String> = emptyList(),
  @XmlPath("CarriedTransportEquipmentSequenceNumberParameterScope/SequenceNumeric") val carriedEquipmentSeq: List<Int> = emptyList(),
) {
  companion object {
    fun render(criteria: ParameterIDSetCriteria?): String {
      if (criteria == null) return ""
      return buildString {
        criteria.acceptanceDate?.let {
          append("<CarrierAcceptanceDateParameterScope><SpecifiedDateTime>${it.render()}</SpecifiedDateTime></CarrierAcceptanceDateParameterScope>")
        }
        criteria.acceptanceCountry?.let {
          append("<CarrierAcceptanceCountryParameterScope><CountryID>$it</CountryID></CarrierAcceptanceCountryParameterScope>")
        }
        criteria.deliveryDate?.let {
          append("<DeliveryDateParameterScope><SpecifiedDateTime>${it.render()}</SpecifiedDateTime></DeliveryDateParameterScope>")
        }
        criteria.deliveryCountry?.let {
          append("<DeliveryCountryParameterScope><CountryID>$it</CountryID></DeliveryCountryParameterScope>")
        }
        criteria.dangerousGoods?.let {
          append("<DangerousGoodsIndicationCodeParameterScope><DangerousGoodsIndicationParameterCode>$it</DangerousGoodsIndicationParameterCode></DangerousGoodsIndicationCodeParameterScope>")
        }
        criteria.mainTransportId?.let {
          append("<MainCarriageTransportMeansIDParameterScope><ID>$it</ID></MainCarriageTransportMeansIDParameterScope>")
        }
        criteria.transportMode?.let {
          append("<MainCarriageModeCodeParameterScope><TransportModeParameterCode>$it</TransportModeParameterCode></MainCarriageModeCodeParameterScope>")
        }
        criteria.mainTransportType?.let {
          append("<MainCarriageTransportMeansTypeCodeParameterScope><TransportMeansParameterCode>$it</TransportMeansParameterCode></MainCarriageTransportMeansTypeCodeParameterScope>")
        }
        criteria.transportRegCountry?.let {
          append("<TransportMeansRegistrationCountryParameterScope><CountryID>$it</CountryID></TransportMeansRegistrationCountryParameterScope>")
        }
        criteria.loadingDate?.let {
          append("<MainCarriageLoadingDateParameterScope><SpecifiedDateTime>${it.render()}</SpecifiedDateTime></MainCarriageLoadingDateParameterScope>")
        }
        criteria.loadingCountry?.let {
          append("<MainCarriageLoadingCountryParameterScope><CountryID>$it</CountryID></MainCarriageLoadingCountryParameterScope>")
        }
        criteria.unloadingDate?.let {
          append("<MainCarriageUnloadingDateParameterScope><SpecifiedDateTime>${it.render()}</SpecifiedDateTime></MainCarriageUnloadingDateParameterScope>")
        }
        criteria.unloadingCountry?.let {
          append("<MainCarriageUnloadingCountryParameterScope><CountryID>$it</CountryID></MainCarriageUnloadingCountryParameterScope>")
        }
        if (criteria.usedEquipmentIds.isNotEmpty()) {
          append("<UsedTransportEquipmentIDParameterScope>")
          criteria.usedEquipmentIds.forEach { id -> append("<ID>$id</ID>") }
          append("</UsedTransportEquipmentIDParameterScope>")
        }
        if (criteria.usedEquipmentCategories.isNotEmpty()) {
          append("<UsedTransportEquipmentCategoryCodeParameterScope>")
          criteria.usedEquipmentCategories.forEach { code -> append("<TransportEquipmentCategoryParameterCode>$code</TransportEquipmentCategoryParameterCode>") }
          append("</UsedTransportEquipmentCategoryCodeParameterScope>")
        }
        if (criteria.usedEquipmentCountries.isNotEmpty()) {
          append("<UsedTransportEquipmentRegistrationCountryParameterScope>")
          criteria.usedEquipmentCountries.forEach { cc -> append("<CountryID>$cc</CountryID>") }
          append("</UsedTransportEquipmentRegistrationCountryParameterScope>")
        }
        if (criteria.usedEquipmentSeq.isNotEmpty()) {
          append("<UsedTransportEquipmentSequenceNumberParameterScope>")
          criteria.usedEquipmentSeq.forEach { seq -> append("<SequenceNumeric>$seq</SequenceNumeric>") }
          append("</UsedTransportEquipmentSequenceNumberParameterScope>")
        }
        if (criteria.carriedEquipmentIds.isNotEmpty()) {
          append("<CarriedTransportEquipmentIDParameterScope>")
          criteria.carriedEquipmentIds.forEach { id -> append("<ID>$id</ID>") }
          append("</CarriedTransportEquipmentIDParameterScope>")
        }
        if (criteria.carriedEquipmentCategories.isNotEmpty()) {
          append("<CarriedTransportEquipmentCategoryCodeParameterScope>")
          criteria.carriedEquipmentCategories.forEach { code -> append("<TransportEquipmentCategoryParameterCode>$code</TransportEquipmentCategoryParameterCode>") }
          append("</CarriedTransportEquipmentCategoryCodeParameterScope>")
        }
        if (criteria.carriedEquipmentSeq.isNotEmpty()) {
          append("<CarriedTransportEquipmentSequenceNumberParameterScope>")
          criteria.carriedEquipmentSeq.forEach { seq -> append("<SequenceNumeric>$seq</SequenceNumeric>") }
          append("</CarriedTransportEquipmentSequenceNumberParameterScope>")
        }
      }
    }
  }
}
