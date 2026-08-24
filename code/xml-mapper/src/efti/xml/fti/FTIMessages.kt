package efti.xml.fti

import efti.domain.UIL
import efti.subsets.CountryCode
import efti.subsets.Subset
import efti.xml.dropXmlRoot
import efti.xml.edifactDateTimeFormats
import efti.xml.extractXmlTag
import klite.Capitalize
import klite.Converter
import klite.KeyConverter
import klite.StatusCode
import klite.html.each
import klite.html.unaryPlus
import klite.json.JsonIgnore
import klite.nodes.Node
import klite.xml.XmlParser
import klite.xml.XmlPath
import org.intellij.lang.annotations.Language
import java.time.Instant
import java.util.*

const val rsmNsPrefix = "urn:eu:move:eFTI:data:standard:"
const val udtNs = "urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34"
const val ramNs = "urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34"

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
  @XmlPath("DateTimeString") val value: String = edifactDateTimeFormats[formatId]!!.format(Instant.now())
) {
  @JsonIgnore val instant get() = edifactDateTimeFormats[formatId]!!.parse(value, Instant::from)
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
  val referencedId: List<UUID>? = null,
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
    referencedId?.forEach { append("<ReferencedID schemeVersionID=\"RFC 9562-4\">$it</ReferencedID>") }
    append("<RequestedSpecifiedQuery>${queryId.render()}</RequestedSpecifiedQuery>")
    requesterCountry?.let { append("<RequesterTradeParty><PostalTradeAddress><CountryID>$it</CountryID></PostalTradeAddress></RequesterTradeParty>") }
    append("</rsm:ExchangedDocument>")
  }
}

data class BinaryFile(
  @XmlPath("FileName") val fileName: String,
  @XmlPath("MIMECode") val mimeType: String,
  @XmlPath("IncludedBinaryObject") val base64Content: String,
) {
  @Language("xml") fun render() =
    """<AttachedSpecifiedBinaryFile><FileName>$fileName</FileName><MIMECode>$mimeType</MIMECode><IncludedBinaryObject format="base64Binary">$base64Content</IncludedBinaryObject></AttachedSpecifiedBinaryFile>"""
}

// --- Request message classes ---

interface FTIMessage {
  val context: ExchangedDocumentContext
  val document: ExchangedDocument
}

data class FTI004UploadIdentifierRequest(
  @XmlPath("ExchangedDocument") override val document: ExchangedDocument,
  @XmlPath("EFTIIDInformation/UniqueIDSetUniqueIDSet") val content: UniqueIDSetUniqueIDSet,
  @XmlPath("ExchangedDocumentContext") override val context: ExchangedDocumentContext = ExchangedDocumentContext(),
): FTIMessage {
  @Language("xml") fun render() = renderWith("""
    <rsm:EFTIIDInformation>${content.render()}</rsm:EFTIIDInformation>
  """)
}

data class FTI009GetCmdsRequest(
  @XmlPath("ExchangedDocument") override val document: ExchangedDocument,
  @XmlPath("MessageInformation/SubsetID") val subsets: List<Subset> = emptyList(),
  @XmlPath("EFTIIDInformation/UniqueIDSetUniqueIDSet") val uil: UIL,
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
  @XmlPath("EFTIIDInformation/UniqueIDSetUniqueIDSet") val uil: UIL,
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
  @XmlPath("EFTIIDInformation/UniqueIDSetUniqueIDSet") val uil: UIL,
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
  @XmlPath("EFTIIDInformation/UniqueIDSetUniqueIDSet") val content: List<UniqueIDSetUniqueIDSet>? = null,
  @XmlPath("ExchangedDocumentContext") override val context: ExchangedDocumentContext = ExchangedDocumentContext(),
): FTIMessage {
  @Language("xml") fun render(xmls: List<String>? = null) = renderWith("""
    <rsm:EFTIIDInformation>${xmls?.joinToString("") ?: content?.joinToString("") { it.render() }}</rsm:EFTIIDInformation>
  """)
}

data class FTI029UploadIdentifierResponse(
  @XmlPath("ExchangedDocument") override val document: ExchangedDocument,
  @XmlPath("EFTIIDInformation/UniqueIDSetUniqueIDSet") val uil: UIL,
  @XmlPath("ExchangedDocumentContext") override val context: ExchangedDocumentContext = ExchangedDocumentContext(),
): FTIMessage {
  @Language("xml") fun render() = renderWith("""
    <rsm:EFTIIDInformation>${uil.render()}</rsm:EFTIIDInformation>
  """)
}

data class FTI030LodgeFollowUpCommResponse(
  @XmlPath("ExchangedDocument") override val document: ExchangedDocument,
  @XmlPath("EFTIIDInformation/UniqueIDSetUniqueIDSet") val uil: UIL,
  @XmlPath("ExchangedDocumentContext") override val context: ExchangedDocumentContext = ExchangedDocumentContext(),
): FTIMessage {
  @Language("xml") fun render() = renderWith("""
    <rsm:EFTIIDInformation>${uil.render()}</rsm:EFTIIDInformation>
  """)
}

@Language("xml") internal fun UIL.render() = UniqueIDSetUniqueIDSet(this).render()

@Language("xml") private fun render(tag: String, content: String) =
  """<rsm:$tag xmlns:rsm="$rsmNsPrefix$tag:1" xmlns="$ramNs" xmlns:udt="$udtNs">$content</rsm:$tag>"""

@Language("xml") private fun FTIMessage.renderWith(content: String) =
  render(this.javaClass.simpleName, "${this.context.render()}${this.document.render()}$content")
