package efti.xml.fti

import ch.tutteli.atrium.api.fluent.en_GB.notToContain
import ch.tutteli.atrium.api.fluent.en_GB.toContain
import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import efti.domain.DangerousGoods
import efti.domain.GateId
import efti.domain.Mode
import efti.domain.PlatformId
import efti.subsets.CountryCode.*
import efti.subsets.Subset
import efti.xml.fti.FTIResponseCode.Completed
import klite.uuid
import klite.xml.XmlParser
import org.junit.jupiter.api.Test
import java.io.File
import java.io.StringReader
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

class FTIMessagesTest {
  val xsdDir = File("xsd")
  val parser = XmlParser(keys = FtiCapitalize)

  val xsdValidators = listOf("FTI004", "FTI009", "FTI010", "FTI019", "FTI021", "FTI025", "FTI029", "FTI030").associateWith { type ->
    SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema").newSchema(
      StreamSource(File(xsdDir, "$type/${type}s.xsd"))
    ).newValidator()
  }

  val docId = "0f6c30b4-89b5-11f1-a20a-3c9c0f2eb459".uuid
  val queryId = "17022113-89b5-11f1-bec0-3c9c0f2eb459".uuid
  val context = ExchangedDocumentContext()
  val document = ExchangedDocument("004", id = docId, queryId = queryId, issueDateTime = DateTimeString(value = "202109240850+0000"))

  private fun validateXsd(type: String, xml: String) {
    xsdValidators[type]!!.validate(StreamSource(StringReader(xml)))
  }

  @Test fun parseFTI004() {
    val xml = File(xsdDir, "FTI004/sample.xml").readText()
    validateXsd("FTI004", xml)

    val req = parser.parse<FTI004UploadIdentifierRequest>(xml)

    expect(req.context).toEqual(context)
    expect(req.document).toEqual(document)
    expect(req.content.uil.gateId).toEqual(GateId("EU-EE"))
    expect(req.content.uil.platformId).toEqual(PlatformId("mock"))
    expect(req.content.uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
    expect(req.content.criteria?.acceptanceCountry).toEqual(DE)
    expect(req.content.criteria?.transportMode).toEqual(Mode("1"))

    val rendered = req.render()
    expect(parser.parse<FTI004UploadIdentifierRequest>(rendered)).toEqual(req)
    validateXsd("FTI004", rendered)
  }

  @Test fun parseFTI009() {
    val xml = File(xsdDir, "FTI009/sample.xml").readText()
    validateXsd("FTI009", xml)
    val req = parser.parse<FTI009GetCmdsRequest>(xml)

    expect(req.context).toEqual(context)
    expect(req.document).toEqual(document.copy(typeCode = "009", requesterCountry = DE))
    expect(req.subsets).toEqual(listOf(Subset("EE05b")))
    expect(req.uil.gateId).toEqual(GateId("EU-EE"))
    expect(req.uil.platformId).toEqual(PlatformId("mock"))
    expect(req.uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)

    val rendered = req.render()
    expect(parser.parse<FTI009GetCmdsRequest>(rendered)).toEqual(req)
    validateXsd("FTI009", rendered)
  }

  @Test fun parseFTI019() {
    val xml = File(xsdDir, "FTI019/sample.xml").readText()
    validateXsd("FTI019", xml)
    val req = parser.parse<FTI019SearchIdentifierRequest>(xml)

    expect(req.context).toEqual(context)
    expect(req.document).toEqual(document.copy(typeCode = "019", requesterCountry = DE))
    expect(req.searchCriteria.acceptanceCountry?.country).toEqual(DE)
    expect(req.searchCriteria.transportMode?.mode).toEqual(Mode("1"))

    val rendered = req.render()
    expect(parser.parse<FTI019SearchIdentifierRequest>(rendered)).toEqual(req)
    validateXsd("FTI019", rendered)
  }

  @Test fun parseFTI025() {
    val xml = File(xsdDir, "FTI025/sample.xml").readText()
    validateXsd("FTI025", xml)
    val req = parser.parse<FTI025LodgeFollowUpCommRequest>(xml)

    expect(req.context).toEqual(context)
    expect(req.document).toEqual(document.copy(typeCode = "025", requesterCountry = DE, referencedIds = listOf("158a5343-9fb4-11f1-ba2a-3c9c0f2eb459".uuid)))
    expect(req.followUp.message).toEqual("Follow-up: correction of consignee address")
    expect(req.uil.gateId).toEqual(GateId("EU-EE"))
    expect(req.uil.platformId).toEqual(PlatformId("mock"))
    expect(req.uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)

    val rendered = req.render()
    expect(parser.parse<FTI025LodgeFollowUpCommRequest>(rendered)).toEqual(req)
    validateXsd("FTI025", rendered)
  }

  @Test fun parseFTI010() {
    val xml = File(xsdDir, "FTI010/sample.xml").readText()
    validateXsd("FTI010", xml)
    val resp = parser.parse<FTI010GetCmdsResponse>(xml)

    expect(resp.context).toEqual(context)
    expect(resp.document).toEqual(document.copy(typeCode = "010", responseCode = Completed))
    expect(resp.subsets).toEqual(listOf(Subset("EE05b")))
    expect(resp.uil.gateId).toEqual(GateId("EU-EE"))
    expect(resp.consignment!!["grossWeightMeasure"]).toEqual("15000.00")

    val rendered = resp.render(xml.extractSpecifiedSupplyChainConsignment())
    expect(parser.parse<FTI010GetCmdsResponse>(rendered)).toEqual(resp)
    validateXsd("FTI010", rendered)
  }

  @Test fun parseFTI021() {
    val xml = File(xsdDir, "FTI021/sample.xml").readText()
    validateXsd("FTI021", xml)
    val resp = parser.parse<FTI021SearchIdentifierResponse>(xml)

    expect(resp.context).toEqual(context)
    expect(resp.document).toEqual(document.copy(typeCode = "021", responseCode = Completed))
    expect(resp.content!!.first().uil.gateId).toEqual(GateId("Gate-001"))

    val rendered = resp.render()
    expect(parser.parse<FTI021SearchIdentifierResponse>(rendered)).toEqual(resp)
    validateXsd("FTI021", rendered)
  }

  @Test fun parseFTI029() {
    val xml = File(xsdDir, "FTI029/sample.xml").readText()
    validateXsd("FTI029", xml)
    val resp = parser.parse<FTI029UploadIdentifierResponse>(xml)

    expect(resp.context).toEqual(context)
    expect(resp.document).toEqual(document.copy(typeCode = "029", responseCode = Completed))
    expect(resp.uil.gateId).toEqual(GateId("Gate-001"))

    val rendered = resp.render()
    expect(parser.parse<FTI029UploadIdentifierResponse>(rendered)).toEqual(resp)
    validateXsd("FTI029", rendered)
  }

  @Test fun parseFTI030() {
    val xml = File(xsdDir, "FTI030/sample.xml").readText()
    validateXsd("FTI030", xml)
    val resp = parser.parse<FTI030LodgeFollowUpCommResponse>(xml)

    expect(resp.context).toEqual(context)
    expect(resp.document).toEqual(document.copy(typeCode = "030", responseCode = Completed))
    expect(resp.uil.gateId).toEqual(GateId("Gate-001"))

    val rendered = resp.render()
    expect(parser.parse<FTI030LodgeFollowUpCommResponse>(rendered)).toEqual(resp)
    validateXsd("FTI030", rendered)
  }

  @Test fun renderNullParameterIDSetCriteria() {
    expect(ParameterIDSetCriteria.render(null)).toEqual("")
  }

  @Test fun renderEmptyParameterIDSetCriteria() {
    expect(ParameterIDSetCriteria.render(ParameterIDSetCriteria())).toEqual("")
  }

  @Test fun renderFullParameterIDSetCriteria() {
    val criteria = ParameterIDSetCriteria(
      acceptanceDate = DateTimeString("102", "20210924"),
      acceptanceCountry = DE,
      deliveryDate = DateTimeString("102", "20260924"),
      deliveryCountry = EE,
      dangerousGoods = DangerousGoods.MEDIUM,
      transportMode = Mode.MARINE,
      mainTransportId = "VESSEL-001",
      mainTransportType = "1513",
      transportRegCountry = DE,
      loadingDate = DateTimeString("102", "20210924"),
      loadingCountry = FI,
      unloadingDate = DateTimeString("102", "20210924"),
      unloadingCountry = AE,
      usedEquipmentIds = listOf("TE-001", "TE-002"),
      usedEquipmentCategories = listOf("T10", "T10"),
      usedEquipmentCountries = listOf(DE, DE),
      usedEquipmentSeq = listOf(1, 2),
      carriedEquipmentIds = listOf("TE-003", "TE-004"),
      carriedEquipmentCategories = listOf("BPR", "BPR"),
      carriedEquipmentSeq = listOf(3, 4)
    )
    val xml = ParameterIDSetCriteria.render(criteria)

    expect(xml).toContain("<CarrierAcceptanceDateParameterScope><SpecifiedDateTime><udt:DateTimeString format=\"102\">20210924</udt:DateTimeString></SpecifiedDateTime></CarrierAcceptanceDateParameterScope>")
    expect(xml).toContain("<CarrierAcceptanceCountryParameterScope><CountryID>DE</CountryID></CarrierAcceptanceCountryParameterScope>")
    expect(xml).toContain("<DeliveryDateParameterScope><SpecifiedDateTime><udt:DateTimeString format=\"102\">20260924</udt:DateTimeString></SpecifiedDateTime></DeliveryDateParameterScope>")
    expect(xml).toContain("<DeliveryCountryParameterScope><CountryID>EE</CountryID></DeliveryCountryParameterScope>")
    expect(xml).toContain("<DangerousGoodsIndicationCodeParameterScope><DangerousGoodsIndicationParameterCode>2</DangerousGoodsIndicationParameterCode></DangerousGoodsIndicationCodeParameterScope>")
    expect(xml).toContain("<MainCarriageModeCodeParameterScope><TransportModeParameterCode>1</TransportModeParameterCode></MainCarriageModeCodeParameterScope>")
    expect(xml).toContain("<MainCarriageTransportMeansIDParameterScope><ID>VESSEL-001</ID></MainCarriageTransportMeansIDParameterScope>")
    expect(xml).toContain("<MainCarriageTransportMeansTypeCodeParameterScope><TransportMeansParameterCode>1513</TransportMeansParameterCode></MainCarriageTransportMeansTypeCodeParameterScope>")
    expect(xml).toContain("<TransportMeansRegistrationCountryParameterScope><CountryID>DE</CountryID></TransportMeansRegistrationCountryParameterScope>")
    expect(xml).toContain("<MainCarriageLoadingDateParameterScope><SpecifiedDateTime><udt:DateTimeString format=\"102\">20210924</udt:DateTimeString></SpecifiedDateTime></MainCarriageLoadingDateParameterScope>")
    expect(xml).toContain("<MainCarriageLoadingCountryParameterScope><CountryID>FI</CountryID></MainCarriageLoadingCountryParameterScope>")
    expect(xml).toContain("<MainCarriageUnloadingDateParameterScope><SpecifiedDateTime><udt:DateTimeString format=\"102\">20210924</udt:DateTimeString></SpecifiedDateTime></MainCarriageUnloadingDateParameterScope>")
    expect(xml).toContain("<MainCarriageUnloadingCountryParameterScope><CountryID>AE</CountryID></MainCarriageUnloadingCountryParameterScope>")
    expect(xml).toContain("<UsedTransportEquipmentIDParameterScope><ID>TE-001</ID><ID>TE-002</ID></UsedTransportEquipmentIDParameterScope>")
    expect(xml).toContain("<UsedTransportEquipmentCategoryCodeParameterScope><TransportEquipmentCategoryParameterCode>T10</TransportEquipmentCategoryParameterCode><TransportEquipmentCategoryParameterCode>T10</TransportEquipmentCategoryParameterCode></UsedTransportEquipmentCategoryCodeParameterScope>")
    expect(xml).toContain("<UsedTransportEquipmentRegistrationCountryParameterScope><CountryID>DE</CountryID><CountryID>DE</CountryID></UsedTransportEquipmentRegistrationCountryParameterScope>")
    expect(xml).toContain("<UsedTransportEquipmentSequenceNumberParameterScope><SequenceNumeric>1</SequenceNumeric><SequenceNumeric>2</SequenceNumeric></UsedTransportEquipmentSequenceNumberParameterScope>")
    expect(xml).toContain("<CarriedTransportEquipmentIDParameterScope><ID>TE-003</ID><ID>TE-004</ID></CarriedTransportEquipmentIDParameterScope>")
    expect(xml).toContain("<CarriedTransportEquipmentCategoryCodeParameterScope><TransportEquipmentCategoryParameterCode>BPR</TransportEquipmentCategoryParameterCode><TransportEquipmentCategoryParameterCode>BPR</TransportEquipmentCategoryParameterCode></CarriedTransportEquipmentCategoryCodeParameterScope>")
    expect(xml).toContain("<CarriedTransportEquipmentSequenceNumberParameterScope><SequenceNumeric>3</SequenceNumeric><SequenceNumeric>4</SequenceNumeric></CarriedTransportEquipmentSequenceNumberParameterScope>")
  }

  @Test fun renderPartialParameterIDSetCriteria() {
    val criteria = ParameterIDSetCriteria(
      acceptanceCountry = DE,
      transportMode = Mode.ROAD,
      mainTransportId = "TRUCK-001"
    )
    val xml = ParameterIDSetCriteria.render(criteria)

    expect(xml).toContain("<CarrierAcceptanceCountryParameterScope><CountryID>DE</CountryID></CarrierAcceptanceCountryParameterScope>")
    expect(xml).toContain("<MainCarriageModeCodeParameterScope><TransportModeParameterCode>3</TransportModeParameterCode></MainCarriageModeCodeParameterScope>")
    expect(xml).toContain("<MainCarriageTransportMeansIDParameterScope><ID>TRUCK-001</ID></MainCarriageTransportMeansIDParameterScope>")
    expect(xml).notToContain("DeliveryDateParameterScope")
    expect(xml).notToContain("DangerousGoodsIndicationCodeParameterScope")
  }
}
