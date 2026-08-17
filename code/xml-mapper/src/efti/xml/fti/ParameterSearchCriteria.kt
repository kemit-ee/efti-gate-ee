package efti.xml.fti

import efti.subsets.CountryCode
import efti.domain.Mode
import klite.xml.XmlPath
import org.intellij.lang.annotations.Language

/** Search identifiers request */
data class ParameterSearchCriteria(
  @XmlPath("CarrierAcceptanceDateParameterScope") val acceptanceDate: List<DateScope> = emptyList(),
  @XmlPath("CarrierAcceptanceCountryParameterScope") val acceptanceCountry: CountryScope? = null,
  @XmlPath("DeliveryDateParameterScope") val deliveryDate: List<DateScope> = emptyList(),
  @XmlPath("DeliveryCountryParameterScope") val deliveryCountry: CountryScope? = null,
  @XmlPath("DangerousGoodsIndicationCodeParameterScope") val dangerousGoodsCode: DangerousGoodsScope? = null,
  @XmlPath("MainCarriageTransportMeansIDParameterScope") val mainTransportId: IDScope? = null,
  @XmlPath("MainCarriageModeCodeParameterScope") val transportMode: TransportModeScope? = null,
  @XmlPath("MainCarriageTransportMeansTypeCodeParameterScope") val mainTransportType: TransportMeansScope? = null,
  @XmlPath("TransportMeansRegistrationCountryParameterScope") val transportRegCountry: CountryScope? = null,
  @XmlPath("MainCarriageLoadingDateParameterScope") val loadingDate: List<DateScope> = emptyList(),
  @XmlPath("MainCarriageLoadingCountryParameterScope") val loadingCountry: CountryScope? = null,
  @XmlPath("MainCarriageUnloadingDateParameterScope") val unloadingDate: List<DateScope> = emptyList(),
  @XmlPath("MainCarriageUnloadingCountryParameterScope") val unloadingCountry: CountryScope? = null,
  @XmlPath("UsedTransportEquipmentIDParameterScope") val usedEquipmentId: IDScope? = null,
  @XmlPath("UsedTransportEquipmentCategoryCodeParameterScope") val usedEquipmentCategory: EquipmentCategoryScope? = null,
  @XmlPath("UsedTransportEquipmentRegistrationCountryParameterScope") val usedEquipmentCountry: CountryScope? = null,
  @XmlPath("UsedTransportEquipmentSequenceNumberParameterScope") val usedEquipmentSeq: SequenceScope? = null,
  @XmlPath("CarriedTransportEquipmentIDParameterScope") val carriedEquipmentId: IDScope? = null,
  @XmlPath("CarriedTransportEquipmentCategoryCodeParameterScope") val carriedEquipmentCategory: EquipmentCategoryScope? = null,
  @XmlPath("CarriedTransportEquipmentSequenceNumberParameterScope") val carriedEquipmentSeq: SequenceScope? = null,
) {
  enum class DateSearchOperator {
    EQ, GE, GT, LE, LT, NE
  }

  /** XSD: CodeType, XML: SubtypeCode */
  enum class SearchOperator {
    EQ, NE
  }

  data class IDScope(
    @XmlPath("ID") val id: String,
    @XmlPath("SubtypeCode") val operator: SearchOperator = SearchOperator.EQ,
  ) {
    @Language("xml") fun render(tag: String) = "<$tag><SubtypeCode>$operator</SubtypeCode><ID>$id</ID></$tag>"
  }

  data class DateScope(
    @XmlPath("SpecifiedDateTime") val date: DateTimeString,
    @XmlPath("SubtypeCode") val operator: DateSearchOperator = DateSearchOperator.EQ,
  ) {
    @Language("xml") fun render(tag: String) = "<$tag><SubtypeCode>$operator</SubtypeCode><SpecifiedDateTime>${date.render()}</SpecifiedDateTime></$tag>"
  }

  data class CountryScope(
    @XmlPath("CountryID") val country: CountryCode,
    @XmlPath("SubtypeCode") val operator: SearchOperator = SearchOperator.EQ,
  ) {
    @Language("xml") fun render(tag: String) = "<$tag><SubtypeCode>$operator</SubtypeCode><CountryID>$country</CountryID></$tag>"
  }

  data class SequenceScope(
    @XmlPath("SequenceNumeric") val sequence: Int,
    @XmlPath("SubtypeCode") val operator: SearchOperator = SearchOperator.EQ,
  ) {
    @Language("xml") fun render(tag: String) = "<$tag><SubtypeCode>$operator</SubtypeCode><SequenceNumeric>$sequence</SequenceNumeric></$tag>"
  }

  data class EquipmentCategoryScope(
    @XmlPath("TransportEquipmentCategoryParameterCode") val code: String,
    @XmlPath("SubtypeCode") val operator: SearchOperator = SearchOperator.EQ,
  ) {
    @Language("xml") fun render(tag: String) = "<$tag><SubtypeCode>$operator</SubtypeCode><TransportEquipmentCategoryParameterCode>$code</TransportEquipmentCategoryParameterCode></$tag>"
  }

  data class TransportMeansScope(
    @XmlPath("TransportMeansParameterCode") val code: String,
    @XmlPath("SubtypeCode") val operator: SearchOperator = SearchOperator.EQ,
  ) {
    @Language("xml") fun render(tag: String) = "<$tag><SubtypeCode>$operator</SubtypeCode><TransportMeansParameterCode>$code</TransportMeansParameterCode></$tag>"
  }

  data class TransportModeScope(
    @XmlPath("TransportModeCodeType") val mode: Mode,
    @XmlPath("SubtypeCode") val operator: SearchOperator = SearchOperator.EQ,
  ) {
    @Language("xml") fun render(tag: String) = "<$tag><SubtypeCode>$operator</SubtypeCode><TransportModeCodeType>$mode</TransportModeCodeType></$tag>"
  }

  data class DangerousGoodsScope(
    @XmlPath("DangerousGoodsIndicationParameterCode") val code: String,
    @XmlPath("SubtypeCode") val operator: SearchOperator = SearchOperator.EQ,
  ) {
    @Language("xml") fun render(tag: String) = "<$tag><SubtypeCode>$operator</SubtypeCode><DangerousGoodsIndicationParameterCode>$code</DangerousGoodsIndicationParameterCode></$tag>"
  }

  @Language("xml") fun render() = "<ParameterSearchCriteria>" + buildString {
    acceptanceDate.forEach { append(it.render("CarrierAcceptanceDateParameterScope")) }
    acceptanceCountry?.let { append(it.render("CarrierAcceptanceCountryParameterScope")) }
    deliveryDate.forEach { append(it.render("DeliveryDateParameterScope")) }
    deliveryCountry?.let { append(it.render("DeliveryCountryParameterScope")) }
    dangerousGoodsCode?.let { append(it.render("DangerousGoodsIndicationCodeParameterScope")) }
    mainTransportId?.let { append(it.render("MainCarriageTransportMeansIDParameterScope")) }
    transportMode?.let { append(it.render("MainCarriageModeCodeParameterScope")) }
    mainTransportType?.let { append(it.render("MainCarriageTransportMeansTypeCodeParameterScope")) }
    transportRegCountry?.let { append(it.render("TransportMeansRegistrationCountryParameterScope")) }
    loadingDate.forEach { append(it.render("MainCarriageLoadingDateParameterScope")) }
    loadingCountry?.let { append(it.render("MainCarriageLoadingCountryParameterScope")) }
    unloadingDate.forEach { append(it.render("MainCarriageUnloadingDateParameterScope")) }
    unloadingCountry?.let { append(it.render("MainCarriageUnloadingCountryParameterScope")) }
    usedEquipmentId?.let { append(it.render("UsedTransportEquipmentIDParameterScope")) }
    usedEquipmentCategory?.let { append(it.render("UsedTransportEquipmentCategoryCodeParameterScope")) }
    usedEquipmentCountry?.let { append(it.render("UsedTransportEquipmentRegistrationCountryParameterScope")) }
    usedEquipmentSeq?.let { append(it.render("UsedTransportEquipmentSequenceNumberParameterScope")) }
    carriedEquipmentId?.let { append(it.render("CarriedTransportEquipmentIDParameterScope")) }
    carriedEquipmentCategory?.let { append(it.render("CarriedTransportEquipmentCategoryCodeParameterScope")) }
    carriedEquipmentSeq?.let { append(it.render("CarriedTransportEquipmentSequenceNumberParameterScope")) }
  } + "</ParameterSearchCriteria>"
}
