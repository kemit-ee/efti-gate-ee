package efti.xml.fti

import efti.xml.dropXmlRoot
import efti.xml.extractXmlTag
import efti.domain.UIL
import efti.subsets.CountryCode
import efti.subsets.Subset
import efti.xml.edifactDateTimeFormats
import klite.Capitalize
import klite.Converter
import klite.KeyConverter
import klite.StatusCode
import klite.html.each
import klite.html.unaryPlus
import klite.nodes.Node
import klite.xml.XmlParser
import klite.xml.XmlPath
import org.intellij.lang.annotations.Language
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.*

object FtiCapitalize: KeyConverter() {
  override fun to(o: String) = Capitalize.to(o).replace("Id", "ID")
  override fun from(o: String) = Capitalize.from(o).replace("ID", "Id")
}

val xmlParser = XmlParser(keys = FtiCapitalize)

enum class FTIResponseCode {
  Completed,
  Error,
  Pending,
  Terminated,
  Unauthorized;

  override fun toString() = name.substring(0, 1)

  fun toStatusCode() = when (this) {
    Completed -> StatusCode.OK
    Error -> StatusCode.InternalServerError
    Pending -> StatusCode.Accepted
    Terminated -> StatusCode.Gone
    Unauthorized -> StatusCode.Unauthorized
  }

  companion object {
    init {
      Converter.use { entries.first { s -> s.toString() == it } }
    }
  }
}

data class DateTimeString(
  @XmlPath("DateTimeString/@format") val formatId: String = "207",
  @XmlPath("DateTimeString") val value: String = edifactDateTimeFormats[formatId]!!.format(Instant.now().atZone(UTC))
) {
  val instant get() = edifactDateTimeFormats[formatId]!!.parse(value, Instant::from)
  @Language("xml") fun render() = """<udt:DateTimeString format="$formatId">$value</udt:DateTimeString>"""
}

// --- Common envelope types ---

data class ExchangedDocumentContext(
  @XmlPath("MessageStandardSpecifiedDocumentContextParameter/ID") val regulationId: String = "eFTI",
  @XmlPath("MessageStandardSpecifiedDocumentContextParameter/SpecifiedDocumentVersion/ID") val version: String = "1.0"
) {
  @Language("xml") fun render() =
    """<rsm:ExchangedDocumentContext><MessageStandardSpecifiedDocumentContextParameter><ID>$regulationId</ID><SpecifiedDocumentVersion><ID>$version</ID></SpecifiedDocumentVersion></MessageStandardSpecifiedDocumentContextParameter></rsm:ExchangedDocumentContext>"""
}

internal fun UUID.render() = "<ID schemeID=\"RFC 9562-4\">$this</ID>"

const val udtNs = "urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34"

fun String.extractFTITag(tagName: String) = extractXmlTag(tagName, setOf(udtNs))
fun String.extractParameterIDSetCriteria() = extractFTITag("ParameterIDSetCriteria")
fun String.extractSpecifiedSupplyChainConsignment() = extractFTITag("SpecifiedSupplyChainConsignment")

data class IncludedNote(
  val content: String,
  @XmlPath("ContentCode") val contentCode: StatusCode? = null,
) {
  @Language("xml") fun render() =
    """<IncludedNote>${contentCode?.let { "<ContentCode>$contentCode</ContentCode>" }}<Content languageID="en">${+content}</Content></IncludedNote>"""
}

data class ExchangedDocument(
  val typeCode: String, // e.g. 004
  @XmlPath("RequestedSpecifiedQuery/ID") val queryId: UUID,
  val id: UUID = UUID.randomUUID(),
  val issueDateTime: DateTimeString = DateTimeString(),
  @XmlPath("RequesterTradeParty/PostalTradeAddress/CountryID") val requesterCountry: CountryCode? = null,
  val disposition: String? = null,
  @XmlPath("StatusCode") val responseCode: FTIResponseCode? = null,
  val includedNote: IncludedNote? = null,
) {
  @Language("xml") fun render() = buildString {
    append("<rsm:ExchangedDocument>")
    append(id.render())
    append("<TypeCode>$typeCode</TypeCode>")
    responseCode?.let { append("<StatusCode>$it</StatusCode>") }
    append("<IssueDateTime>${issueDateTime.render()}</IssueDateTime>")
    includedNote?.let { append("<IncludedNote>${it.render()}</IncludedNote>") }
    disposition?.let { append("<Disposition>${+it}</Disposition>") }
    append("<RequestedSpecifiedQuery>${queryId.render()}</RequestedSpecifiedQuery>")
    requesterCountry?.let { append("<RequesterTradeParty><PostalTradeAddress><CountryID>$it</CountryID></PostalTradeAddress></RequesterTradeParty>") }
    append("</rsm:ExchangedDocument>")
  }
}

data class BinaryFile(
  @XmlPath("FileName") val fileName: String,
  @XmlPath("MIMECode") val mimeType: String,
  @XmlPath("EncodingCode") val encodingType: String = "7", // UTF-8
  @XmlPath("CharacterSetCode") val charsetCode: String = "3", // US-ASCII (or Unicode actually?)
  @XmlPath("IncludedBinaryObject") val base64Content: String,
) {
  @Language("xml") fun render() =
    """<AttachedSpecifiedBinaryFile><FileName>$fileName</FileName><MIMECode>$mimeType</MIMECode><EncodingCode>$encodingType</EncodingCode><CharacterSetCode>$charsetCode</CharacterSetCode><IncludedBinaryObject format="base64Binary">$base64Content</IncludedBinaryObject></AttachedSpecifiedBinaryFile>"""
}

// --- Request message classes ---

interface FTIMessage {
  val context: ExchangedDocumentContext
  val document: ExchangedDocument
}

data class FTI004UploadIdentifierRequest(
  @XmlPath("ExchangedDocument") override val document: ExchangedDocument,
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val content: UniqueIDSetUIL,
  @XmlPath("ExchangedDocumentContext") override val context: ExchangedDocumentContext = ExchangedDocumentContext(),
): FTIMessage {
  @Language("xml") fun render() = renderWith("""
    <rsm:EFTIIDInformation>${content.render()}</rsm:EFTIIDInformation>
  """)
}

data class FTI009GetCmdsRequest(
  @XmlPath("ExchangedDocument") override val document: ExchangedDocument,
  @XmlPath("MessageInformation/SubsetID") val subsets: List<Subset> = emptyList(),
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val uil: UIL,
  @XmlPath("ExchangedDocumentContext") override val context: ExchangedDocumentContext = ExchangedDocumentContext(),
): FTIMessage {
  @Language("xml") fun render() = renderWith("""
    <rsm:MessageInformation>${subsets.each { (i, s) -> "<SubsetID>$s</SubsetID>" }}</rsm:MessageInformation>
    <rsm:EFTIIDInformation>${uil.render()}</rsm:EFTIIDInformation>
  """)
}

data class FTI019SearchIdentifierRequest(
  @XmlPath("ExchangedDocument") override val document: ExchangedDocument,
  @XmlPath("EFTIIDInformation/ParameterSearchCriteria") val searchCriteria: ParameterSearchCriteria,
  @XmlPath("ExchangedDocumentContext") override val context: ExchangedDocumentContext = ExchangedDocumentContext(),
): FTIMessage {
  @Language("xml") fun render() = renderWith("""
    <rsm:EFTIIDInformation>${searchCriteria.render()}</rsm:EFTIIDInformation>
  """)
}

data class FTI025LodgeFollowUpCommRequest(
  @XmlPath("ExchangedDocument") override val document: ExchangedDocument,
  @XmlPath("MessageInformation/FollowUp") val followUp: String? = null,
  @XmlPath("MessageInformation/AttachedSpecifiedBinaryFile") val files: List<BinaryFile> = emptyList(),
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val uil: UIL,
  @XmlPath("ExchangedDocumentContext") override val context: ExchangedDocumentContext = ExchangedDocumentContext(),
): FTIMessage {
  @Language("xml") fun render() = renderWith("""
    <rsm:MessageInformation>${followUp?.let { "<FollowUp languageID=\"en\">$it</FollowUp>" } ?: ""}${files.joinToString("") { it.render() }}</rsm:MessageInformation>
    <rsm:EFTIIDInformation>${uil.render()}</rsm:EFTIIDInformation>
  """)
}

// --- Response message classes (outgoing from gate) ---

typealias SpecifiedSupplyChainConsignment = Node

data class FTI010GetCmdsResponse(
  @XmlPath("ExchangedDocument") override val document: ExchangedDocument,
  @XmlPath("MessageInformation/SubsetID") val subsets: List<Subset> = emptyList(),
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val uil: UIL,
  @XmlPath("SpecifiedSupplyChainConsignment") val consignment: SpecifiedSupplyChainConsignment? = null,
  @XmlPath("ExchangedDocumentContext") override val context: ExchangedDocumentContext = ExchangedDocumentContext(),
): FTIMessage {
  @Language("xml") fun render(consignmentXml: String? = null) = renderWith("""
    <rsm:MessageInformation>${subsets.joinToString("") { "<SubsetID>$it</SubsetID>" }}</rsm:MessageInformation>
    <rsm:EFTIIDInformation>${uil.render()}</rsm:EFTIIDInformation>
    ${consignmentXml?.dropXmlRoot()?.let { "<rsm:SpecifiedSupplyChainConsignment>$it</rsm:SpecifiedSupplyChainConsignment>" } ?: ""}
  """)
}

data class FTI021SearchIdentifierResponse(
  @XmlPath("ExchangedDocument") override val document: ExchangedDocument,
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val content: List<UniqueIDSetUIL>,
  @XmlPath("ExchangedDocumentContext") override val context: ExchangedDocumentContext = ExchangedDocumentContext(),
): FTIMessage {
  @Language("xml") fun render() = renderWith("""
    <rsm:EFTIIDInformation>${content.joinToString("") { it.render() }}</rsm:EFTIIDInformation>
  """)
}

data class FTI029UploadIdentifierResponse(
  @XmlPath("ExchangedDocument") override val document: ExchangedDocument,
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val uil: UIL,
  @XmlPath("ExchangedDocumentContext") override val context: ExchangedDocumentContext = ExchangedDocumentContext(),
): FTIMessage {
  @Language("xml") fun render() = renderWith("""
    <rsm:EFTIIDInformation>${uil.render()}</rsm:EFTIIDInformation>
  """)
}

data class FTI030LodgeFollowUpCommResponse(
  @XmlPath("ExchangedDocument") override val document: ExchangedDocument,
  @XmlPath("EFTIIDInformation/UniqueIDSetUIL") val uil: UIL,
  @XmlPath("ExchangedDocumentContext") override val context: ExchangedDocumentContext = ExchangedDocumentContext(),
): FTIMessage {
  @Language("xml") fun render() = renderWith("""
    <rsm:EFTIIDInformation>${uil.render()}</rsm:EFTIIDInformation>
  """)
}

@Language("xml") internal fun UIL.render() =
  """<rsm:UniqueIDSetUIL><GateID>$gateId</GateID><PlatformID>$platformId</PlatformID><DatasetID schemeID="RFC 9562-4">$datasetId</DatasetID></rsm:UniqueIDSetUIL>"""

@Language("xml") internal fun UniqueIDSetUIL.render(): String {
  val criteriaXml = ParameterIDSetCriteria.render(criteria)
  return """<UniqueIDSetUIL><GateID>${uil.gateId}</GateID><PlatformID>${uil.platformId}</PlatformID><DatasetID schemeID="RFC 9562-4">${uil.datasetId}</DatasetID>${if (criteriaXml.isNotEmpty()) "<ParameterIDSetCriteria>$criteriaXml</ParameterIDSetCriteria>" else ""}</UniqueIDSetUIL>"""
}

@Language("xml") private fun render(tag: String, content: String) =
  """<rsm:$tag xmlns:rsm="urn:eu:move:eFTI:data:draft:$tag:1" xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34" xmlns:udt="$udtNs">$content</rsm:$tag>"""

@Language("xml") private fun FTIMessage.renderWith(content: String) =
  render(this.javaClass.simpleName, "${this.context.render()}${this.document.render()}$content")
