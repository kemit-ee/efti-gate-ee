package domain

import domain.ParameterSearchCriteria.SearchOperator.EQ
import klite.Capitalize
import klite.KeyConverter
import klite.nodes.Node
import klite.xml.XmlPath
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object FtiCapitalize: KeyConverter() {
  override fun to(o: String) = Capitalize.to(o).replace("Id", "ID")
  override fun from(o: String) = Capitalize.from(o).replace("ID", "Id")
}

val edifactDateTimeFormats = mapOf(
  "102" to DateTimeFormatter.ofPattern("yyyyMMdd"), // LocalDate
  "203" to DateTimeFormatter.ofPattern("yyyyMMddHHmm"), // LocalDateTime
  "205" to DateTimeFormatter.ofPattern("yyyyMMddHHmmxxxx"), // Instant
  "207" to DateTimeFormatter.ofPattern("yyyyMMddHHmmss"), // LocalDateTime
)

data class DateTimeString(
  @XmlPath("DateTimeString") val value: String = "",
  @XmlPath("DateTimeString/@format") val format: String? = null
) {
  val instant: Instant?
    get() = runCatching {
      when (format) {
        "102" -> edifactDateTimeFormats.getValue(format)
          .parse(value, LocalDate::from)
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant()

        "203", "207" -> edifactDateTimeFormats.getValue(format)
          .parse(value, LocalDateTime::from)
          .atZone(ZoneOffset.UTC)
          .toInstant()

        "205" -> edifactDateTimeFormats.getValue(format)
          .parse(value, Instant::from)

        else -> null
      }
    }.getOrNull()
}

// --- Common envelope types ---

data class ExchangedDocumentContext(
  @XmlPath("MessageStandardSpecifiedDocumentContextParameter/ID") val regulationId: String,
  @XmlPath("MessageStandardSpecifiedDocumentContextParameter/SpecifiedDocumentVersion/ID") val version: String,
)

data class ExchangedDocument(
  @XmlPath("TypeCode") val typeCode: String,
  @XmlPath("StatusCode") val statusCode: String? = null,
  @XmlPath("IssueDateTime") val issueDateTime: DateTimeString,
  @XmlPath("Disposition") val disposition: String? = null,
  @XmlPath("RequesterTradeParty/PostalTradeAddress/CountryID") val requesterCountry: CountryCode? = null,
)

// --- ParameterIDSetCriteria (FTI004/FTI010) ---

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
  @XmlPath("UsedTransportEquipmentSequenceNumberParameterScope/SequenceNumeric") val usedEquipmentSeq: List<String> = emptyList(),

  @XmlPath("CarriedTransportEquipmentIDParameterScope/ID") val carriedEquipmentIds: List<String> = emptyList(),
  @XmlPath("CarriedTransportEquipmentCategoryCodeParameterScope/TransportEquipmentCategoryParameterCode") val carriedEquipmentCategories: List<String> = emptyList(),
  @XmlPath("CarriedTransportEquipmentSequenceNumberParameterScope/SequenceNumeric") val carriedEquipmentSeq: List<String> = emptyList(),
)

// --- ParameterSearchCriteria (FTI019) ---

/** Search identifiers request */
data class ParameterSearchCriteria(
  @XmlPath("CarrierAcceptanceDateParameterScope") val acceptanceDate: List<DateScope> = emptyList(),
  @XmlPath("CarrierAcceptanceCountryParameterScope") val acceptanceCountry: CountryScope? = null,
  @XmlPath("DeliveryDateParameterScope") val deliveryDate: List<DateScope> = emptyList(),
  @XmlPath("DeliveryCountryParameterScope") val deliveryCountry: CountryScope? = null,
  @XmlPath("DangerousGoodsIndicationCodeParameterScope") val dangerousGoodsCode: DangerousGoodsScope? = null,
  @XmlPath("MainCarriageTransportMeansIDParameterScope") val mainTransportId: IDScope? = null,
  @XmlPath("MainCarriageModeCodeParameterScope") val mainMode: TransportModeScope? = null,
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
      @XmlPath("SubtypeCode") val operator: SearchOperator = EQ,
  )

  data class DateScope(
    @XmlPath("SpecifiedDateTime") val date: DateTimeString,
    @XmlPath("SubtypeCode") val operator: DateSearchOperator = DateSearchOperator.EQ,
  )

  data class CountryScope(
    @XmlPath("CountryID") val country: CountryCode,
    @XmlPath("SubtypeCode") val operator: SearchOperator = EQ,
  )

  data class SequenceScope(
    @XmlPath("SequenceNumeric") val sequence: Int,
    @XmlPath("SubtypeCode") val operator: SearchOperator = EQ,
  )

  data class EquipmentCategoryScope(
    @XmlPath("TransportEquipmentCategoryParameterCode") val code: String,
    @XmlPath("SubtypeCode") val operator: SearchOperator = EQ,
  )

  data class TransportMeansScope(
    @XmlPath("TransportMeansParameterCode") val code: String,
    @XmlPath("SubtypeCode") val operator: SearchOperator = EQ,
  )

  data class TransportModeScope(
    @XmlPath("TransportModeCodeType") val mode: Mode,
    @XmlPath("SubtypeCode") val operator: SearchOperator = EQ,
  )

  data class DangerousGoodsScope(
    @XmlPath("DangerousGoodsIndicationParameterCode") val code: String,
    @XmlPath("SubtypeCode") val operator: SearchOperator = EQ,
  )
}

data class UniqueIDSetUIL(
  @XmlPath("") val uil: UIL,
  @XmlPath("ParameterIDSetCriteria") val criteria: ParameterIDSetCriteria
)

// --- Request message classes ---

data class Fti004UploadIdentifierRequest(
  @XmlPath("ExchangedDocumentContext") val context: ExchangedDocumentContext,
  @XmlPath("ExchangedDocument") val document: ExchangedDocument,
  @XmlPath("ExchangedDocument/ID") val documentId: String,
  @XmlPath("ExchangedDocument/RequestedSpecifiedQuery/ID") val queryId: String? = null,
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val content: UniqueIDSetUIL,
)

data class Fti009GetCmdsRequest(
  @XmlPath("ExchangedDocumentContext") val context: ExchangedDocumentContext,
  @XmlPath("ExchangedDocument") val document: ExchangedDocument,
  @XmlPath("ExchangedDocument/ID") val documentId: String,
  @XmlPath("ExchangedDocument/RequestedSpecifiedQuery/ID") val queryId: String? = null,
  @XmlPath("MessageInformation/SubsetID") val subsetIds: List<String> = emptyList(),
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val uil: UIL,
)

data class Fti019SearchIdentifierRequest(
  @XmlPath("ExchangedDocumentContext") val context: ExchangedDocumentContext,
  @XmlPath("ExchangedDocument") val document: ExchangedDocument,
  @XmlPath("ExchangedDocument/ID") val documentId: String,
  @XmlPath("ExchangedDocument/RequestedSpecifiedQuery/ID") val queryId: String? = null,
  @XmlPath("EFTIIDInformation/ParameterSearchCriteria") val searchCriteria: ParameterSearchCriteria,
)

data class Fti025LodgeFollowUpCommRequest(
  @XmlPath("ExchangedDocumentContext") val context: ExchangedDocumentContext,
  @XmlPath("ExchangedDocument") val document: ExchangedDocument,
  @XmlPath("ExchangedDocument/ID") val documentId: String,
  @XmlPath("ExchangedDocument/RequestedSpecifiedQuery/ID") val queryId: String? = null,
  @XmlPath("MessageInformation/FollowUp") val followUp: String,
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val uil: UIL,
)

// --- Response message classes (outgoing from gate) ---

data class Fti010GetCmdsResponse(
  @XmlPath("ExchangedDocumentContext") val context: ExchangedDocumentContext,
  @XmlPath("ExchangedDocument") val document: ExchangedDocument,
  @XmlPath("ExchangedDocument/ID") val documentId: String,
  @XmlPath("ExchangedDocument/RequestedSpecifiedQuery/ID") val queryId: String? = null,
  @XmlPath("MessageInformation/SubsetID") val subsetIds: List<String> = emptyList(),
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val uil: UIL,
  @XmlPath("SpecifiedSupplyChainConsignment") val consignment: Node? = null,
)

data class Fti021SearchIdentifierResponse(
  @XmlPath("ExchangedDocumentContext") val context: ExchangedDocumentContext,
  @XmlPath("ExchangedDocument") val document: ExchangedDocument,
  @XmlPath("ExchangedDocument/ID") val documentId: String,
  @XmlPath("ExchangedDocument/RequestedSpecifiedQuery/ID") val queryId: String? = null,
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val content: UniqueIDSetUIL,
)

data class Fti029UploadIdentifierResponse(
  @XmlPath("ExchangedDocumentContext") val context: ExchangedDocumentContext,
  @XmlPath("ExchangedDocument") val document: ExchangedDocument,
  @XmlPath("ExchangedDocument/ID") val documentId: String,
  @XmlPath("ExchangedDocument/RequestedSpecifiedQuery/ID") val queryId: String? = null,
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val uil: UIL,
)

data class Fti030LodgeFollowUpCommResponse(
  @XmlPath("ExchangedDocumentContext") val context: ExchangedDocumentContext,
  @XmlPath("ExchangedDocument") val document: ExchangedDocument,
  @XmlPath("ExchangedDocument/ID") val documentId: String,
  @XmlPath("ExchangedDocument/RequestedSpecifiedQuery/ID") val queryId: String? = null,
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val uil: UIL
)
