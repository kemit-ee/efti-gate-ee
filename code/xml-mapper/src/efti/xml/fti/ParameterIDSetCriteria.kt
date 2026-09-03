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
  @Language("xml") fun render(criteriaXml: String = criteria?.render() ?: ""): String =
    """<UniqueIDSetUniqueIDSet><GateID>${uil.gateId}</GateID><PlatformID>${uil.platformId}</PlatformID><DatasetID schemeID="RFC 9562-4">${uil.datasetId}</DatasetID>$criteriaXml</UniqueIDSetUniqueIDSet>"""
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
  fun render(): String {
    return buildString {
      append("<ParameterIDSetCriteria>")
      acceptanceDate?.let {
        append("<CarrierAcceptanceDateParameterScope><SpecifiedDateTime>${it.render()}</SpecifiedDateTime></CarrierAcceptanceDateParameterScope>")
      }
      acceptanceCountry?.let {
        append("<CarrierAcceptanceCountryParameterScope><CountryID>$it</CountryID></CarrierAcceptanceCountryParameterScope>")
      }
      deliveryDate?.let {
        append("<DeliveryDateParameterScope><SpecifiedDateTime>${it.render()}</SpecifiedDateTime></DeliveryDateParameterScope>")
      }
      deliveryCountry?.let {
        append("<DeliveryCountryParameterScope><CountryID>$it</CountryID></DeliveryCountryParameterScope>")
      }
      dangerousGoods?.let {
        append("<DangerousGoodsIndicationCodeParameterScope><DangerousGoodsIndicationParameterCode>$it</DangerousGoodsIndicationParameterCode></DangerousGoodsIndicationCodeParameterScope>")
      }
      mainTransportId?.let {
        append("<MainCarriageTransportMeansIDParameterScope><ID>$it</ID></MainCarriageTransportMeansIDParameterScope>")
      }
      transportMode?.let {
        append("<MainCarriageModeCodeParameterScope><TransportModeParameterCode>$it</TransportModeParameterCode></MainCarriageModeCodeParameterScope>")
      }
      mainTransportType?.let {
        append("<MainCarriageTransportMeansTypeCodeParameterScope><TransportMeansParameterCode>$it</TransportMeansParameterCode></MainCarriageTransportMeansTypeCodeParameterScope>")
      }
      transportRegCountry?.let {
        append("<TransportMeansRegistrationCountryParameterScope><CountryID>$it</CountryID></TransportMeansRegistrationCountryParameterScope>")
      }
      loadingDate?.let {
        append("<MainCarriageLoadingDateParameterScope><SpecifiedDateTime>${it.render()}</SpecifiedDateTime></MainCarriageLoadingDateParameterScope>")
      }
      loadingCountry?.let {
        append("<MainCarriageLoadingCountryParameterScope><CountryID>$it</CountryID></MainCarriageLoadingCountryParameterScope>")
      }
      unloadingDate?.let {
        append("<MainCarriageUnloadingDateParameterScope><SpecifiedDateTime>${it.render()}</SpecifiedDateTime></MainCarriageUnloadingDateParameterScope>")
      }
      unloadingCountry?.let {
        append("<MainCarriageUnloadingCountryParameterScope><CountryID>$it</CountryID></MainCarriageUnloadingCountryParameterScope>")
      }
      if (usedEquipmentIds.isNotEmpty()) {
        append("<UsedTransportEquipmentIDParameterScope>")
        usedEquipmentIds.forEach { id -> append("<ID>$id</ID>") }
        append("</UsedTransportEquipmentIDParameterScope>")
      }
      if (usedEquipmentCategories.isNotEmpty()) {
        append("<UsedTransportEquipmentCategoryCodeParameterScope>")
        usedEquipmentCategories.forEach { code -> append("<TransportEquipmentCategoryParameterCode>$code</TransportEquipmentCategoryParameterCode>") }
        append("</UsedTransportEquipmentCategoryCodeParameterScope>")
      }
      if (usedEquipmentCountries.isNotEmpty()) {
        append("<UsedTransportEquipmentRegistrationCountryParameterScope>")
        usedEquipmentCountries.forEach { cc -> append("<CountryID>$cc</CountryID>") }
        append("</UsedTransportEquipmentRegistrationCountryParameterScope>")
      }
      if (usedEquipmentSeq.isNotEmpty()) {
        append("<UsedTransportEquipmentSequenceNumberParameterScope>")
        usedEquipmentSeq.forEach { seq -> append("<SequenceNumeric>$seq</SequenceNumeric>") }
        append("</UsedTransportEquipmentSequenceNumberParameterScope>")
      }
      if (carriedEquipmentIds.isNotEmpty()) {
        append("<CarriedTransportEquipmentIDParameterScope>")
        carriedEquipmentIds.forEach { id -> append("<ID>$id</ID>") }
        append("</CarriedTransportEquipmentIDParameterScope>")
      }
      if (carriedEquipmentCategories.isNotEmpty()) {
        append("<CarriedTransportEquipmentCategoryCodeParameterScope>")
        carriedEquipmentCategories.forEach { code -> append("<TransportEquipmentCategoryParameterCode>$code</TransportEquipmentCategoryParameterCode>") }
        append("</CarriedTransportEquipmentCategoryCodeParameterScope>")
      }
      if (carriedEquipmentSeq.isNotEmpty()) {
        append("<CarriedTransportEquipmentSequenceNumberParameterScope>")
        carriedEquipmentSeq.forEach { seq -> append("<SequenceNumeric>$seq</SequenceNumeric>") }
        append("</CarriedTransportEquipmentSequenceNumberParameterScope>")
      }
      append("</ParameterIDSetCriteria>")
    }
  }
}
