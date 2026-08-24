package efti

import ch.tutteli.atrium.api.fluent.en_GB.toContain
import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import efti.domain.GateId
import efti.domain.Mode
import efti.domain.PlatformId
import efti.subsets.CountryCode.DE
import efti.xml.fti.DateTimeString
import efti.xml.fti.ParameterSearchCriteria
import efti.xml.fti.ParameterSearchCriteria.*
import io.mockk.verify
import klite.uuid
import org.junit.jupiter.api.Test
import java.io.File

class SearchRoutesTest : BaseMocks() {
  val routes = SearchRoutes(requestIdHandler)

  val criteria = ParameterSearchCriteria(
    acceptanceCountry = CountryScope(DE),
    transportMode = TransportModeScope(Mode("1")),
    mainTransportId = IDScope("VESSEL-001"),
    acceptanceDate = listOf(DateScope(DateTimeString("207", "202109240850+0000")))
  )

  @Test fun requestToJson() {
    val xml = File("xsd/Normalized/FTI019/sample.xml").readText()
    val result = routes.requestToJson(xml, exchange)

    verify { requestIdHandler.send(exchange, "17022113-89b5-11f1-bec0-3c9c0f2eb459".uuid) }

    expect(result.acceptanceCountry?.country).toEqual(DE)
    expect(result.transportMode?.mode).toEqual(Mode("1"))
    expect(result.mainTransportId?.id).toEqual("VESSEL-001")
  }

  @Test fun requestToXml() {
    val xml = routes.requestToXml(criteria, exchange)

    expect(xml).toContain("<TypeCode>019</TypeCode>")
    expect(xml).toContain("<CarrierAcceptanceCountryParameterScope>")
    expect(xml).toContain("<CountryID>DE</CountryID>")
    expect(xml).toContain("<MainCarriageModeCodeParameterScope>")
    expect(xml).toContain("<TransportModeCodeType>1</TransportModeCodeType>")
    expect(xml).toContain("<MainCarriageTransportMeansIDParameterScope>")
    expect(xml).toContain("<ID>VESSEL-001</ID>")
    expect(xml).toContain("<CarrierAcceptanceDateParameterScope>")
  }

  @Test fun requestToXmlRoundtrip() {
    val xml = routes.requestToXml(criteria, exchange)
    val parsed = routes.requestToJson(xml, exchange)

    verify { requestIdHandler.send(exchange, "00000000-0000-0000-0000-000000000001".uuid) }

    expect(parsed.acceptanceCountry?.country).toEqual(DE)
    expect(parsed.transportMode?.mode).toEqual(Mode("1"))
    expect(parsed.mainTransportId?.id).toEqual("VESSEL-001")
    expect(parsed.acceptanceDate.size).toEqual(1)
  }

  @Test fun responseToJson() {
    val xml = File("xsd/Normalized/FTI021/sample.xml").readText()
    val result = routes.responseToJson(xml)

    expect(result.size).toEqual(1)
    expect(result.first().uil.gateId).toEqual(GateId("Gate-001"))
    expect(result.first().uil.platformId).toEqual(PlatformId("Platform-001"))
    expect(result.first().uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
  }

  @Test fun responseToJsonMultiple() {
    val xml = File("xsd/Normalized/FTI021/sample.xml").readText()
    val combined = "$xml⦀$xml"
    val result = routes.responseToJson(combined)

    expect(result.size).toEqual(2)
    expect(result[0].uil.gateId).toEqual(GateId("Gate-001"))
    expect(result[1].uil.gateId).toEqual(GateId("Gate-001"))
  }
}
