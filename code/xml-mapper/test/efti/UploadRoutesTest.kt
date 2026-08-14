package efti

import ch.tutteli.atrium.api.fluent.en_GB.toContain
import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import efti.domain.GateId
import efti.domain.PlatformId
import efti.domain.UIL
import efti.subsets.CountryCode.DE
import efti.domain.Mode
import efti.xml.fti.FTI029UploadIdentifierResponse
import klite.uuid
import org.junit.jupiter.api.Test
import java.io.File

class UploadRoutesTest {
  val routes = UploadRoutes()

  @Test fun requestToJson() {
    val xml = File("xsd/Normalized/FTI004/sample.xml").readText()
    val result = routes.requestToJson(xml)

    expect(result.uil.gateId).toEqual(GateId("POC"))
    expect(result.uil.platformId).toEqual(PlatformId("demo"))
    expect(result.uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
    expect(result.criteria.acceptanceCountry).toEqual(DE)
    expect(result.criteria.transportMode).toEqual(Mode("1"))
  }

  @Test fun responseToXml() {
    val uil = UIL(PlatformId("demo"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("POC"))
    val xml = routes.responseToXml(uil)

    expect(xml).toContain("<TypeCode>029</TypeCode>")
    expect(xml).toContain("<GateID>POC</GateID>")
    expect(xml).toContain("<PlatformID>demo</PlatformID>")
    expect(xml).toContain("550e8400-e29b-41d4-a716-446655440000")
  }

  @Test fun responseToXmlRoundtrip() {
    val uil = UIL(PlatformId("demo"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("POC"))
    val xml = routes.responseToXml(uil)
    val parsed = routes.xmlParser.parse<FTI029UploadIdentifierResponse>(xml)

    expect(parsed.uil.gateId).toEqual(GateId("POC"))
    expect(parsed.uil.platformId).toEqual(PlatformId("demo"))
    expect(parsed.uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
  }
}
