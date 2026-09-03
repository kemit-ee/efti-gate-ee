package efti

import ch.tutteli.atrium.api.fluent.en_GB.toContain
import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import efti.domain.GateId
import efti.domain.Mode.Companion.MARINE
import efti.domain.PlatformId
import efti.domain.UIL
import efti.subsets.CountryCode.DE
import efti.xml.fti.FTI029UploadIdentifierResponse
import efti.xml.fti.extractParameterIDSetCriteria
import efti.xml.fti.xmlParser
import io.mockk.every
import io.mockk.verify
import klite.uuid
import org.junit.jupiter.api.Test
import java.io.File

class UploadRoutesTest: BaseMocks() {
  val routes = UploadRoutes(requestIdHandler)
  val uil = UIL(PlatformId("mock"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("EU-EE"))

  @Test fun requestToJsonFromFTI004() {
    val xml = File("xsd/FTI004/sample.xml").readText()
    val result = routes.requestToJson(xml, exchange)

    verify { requestIdHandler.send(exchange, "17022113-89b5-11f1-bec0-3c9c0f2eb459".uuid) }

    expect(result.datasetId).toEqual(uil.datasetId)
    expect(result.gateId).toEqual(uil.gateId)
    expect(result.platformId).toEqual(uil.platformId)
    expect(result.acceptanceCountry).toEqual(DE)
    expect(result.transportMode).toEqual(MARINE)
    expect(result.xml).toEqual(xml.extractParameterIDSetCriteria())
  }

  @Test fun requestToJsonFromCriteria() {
    every { exchange.header("X-Platform-Id") } returns "mock"
    every { exchange.header("X-Dataset-Id") } returns "550e8400-e29b-41d4-a716-446655440000"
    every { exchange.header("X-Gate-Id") } returns "EU-EE"

    val xml = """
      <ParameterIDSetCriteria xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
        <CarrierAcceptanceCountryParameterScope><CountryID>DE</CountryID></CarrierAcceptanceCountryParameterScope>
        <MainCarriageModeCodeParameterScope><TransportModeParameterCode>1</TransportModeParameterCode></MainCarriageModeCodeParameterScope>
      </ParameterIDSetCriteria>
    """.trimIndent()
    val result = routes.requestToJson(xml, exchange)

    expect(result.datasetId).toEqual(uil.datasetId)
    expect(result.gateId).toEqual(uil.gateId)
    expect(result.platformId).toEqual(uil.platformId)
    expect(result.acceptanceCountry).toEqual(DE)
    expect(result.transportMode).toEqual(MARINE)
  }

  @Test fun responseToXml() {
    val xml = routes.responseToXml(uil, exchange)

    expect(xml).toContain("<TypeCode>029</TypeCode>")
    expect(xml).toContain("<GateID>EU-EE</GateID>")
    expect(xml).toContain("<PlatformID>mock</PlatformID>")
    expect(xml).toContain("550e8400-e29b-41d4-a716-446655440000")
  }

  @Test fun responseToXmlRoundtrip() {
    val xml = routes.responseToXml(uil, exchange)
    val parsed = xmlParser.parse<FTI029UploadIdentifierResponse>(xml)

    expect(parsed.uil).toEqual(uil)
  }
}
