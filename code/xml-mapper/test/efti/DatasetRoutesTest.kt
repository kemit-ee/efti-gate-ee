package efti

import ch.tutteli.atrium.api.fluent.en_GB.toContain
import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import efti.domain.GateId
import efti.domain.PlatformId
import efti.domain.UIL
import efti.subsets.Subset
import klite.uuid
import org.junit.jupiter.api.Test
import java.io.File

class DatasetRoutesTest {
  val routes = DatasetRoutes()

  @Test fun requestToJson() {
    val xml = File("xsd/Normalized/FTI009/sample.xml").readText()
    val result = routes.requestToJson(xml)

    expect(result.uil.gateId).toEqual(GateId("Gate-001"))
    expect(result.uil.platformId).toEqual(PlatformId("Platform-001"))
    expect(result.uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
    expect(result.subsets).toEqual(listOf(Subset("EE05b")))
  }

  @Test fun requestToXml() {
    val uil = UIL(PlatformId("Platform-001"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("Gate-001"))
    val req = DatasetQueryRequest(uil, listOf(Subset("EE05b")))
    val xml = routes.requestToXml(req)

    expect(xml).toContain("<TypeCode>009</TypeCode>")
    expect(xml).toContain("<GateID>Gate-001</GateID>")
    expect(xml).toContain("<PlatformID>Platform-001</PlatformID>")
    expect(xml).toContain("550e8400-e29b-41d4-a716-446655440000")
    expect(xml).toContain("<SubsetID>EE05b</SubsetID>")
  }

  @Test fun requestToXmlRoundtrip() {
    val uil = UIL(PlatformId("Platform-001"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("Gate-001"))
    val req = DatasetQueryRequest(uil, listOf(Subset("EE05b")))
    val xml = routes.requestToXml(req)
    val parsed = routes.requestToJson(xml)

    expect(parsed.uil.gateId).toEqual(GateId("Gate-001"))
    expect(parsed.uil.platformId).toEqual(PlatformId("Platform-001"))
    expect(parsed.uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
    expect(parsed.subsets).toEqual(listOf(Subset("EE05b")))
  }

  @Test fun responseToJsonFromFTI010() {
    val xml = File("xsd/Normalized/FTI010/sample.xml").readText()
    val result = routes.responseToJson(xml)

    expect(result["grossWeightMeasure"]).toEqual("15000.00")
    expect(result["netWeightMeasure"]).toEqual("12000.00")
  }

  @Test fun responseToXmlPassThrough() {
    val xml = File("xsd/Normalized/FTI010/sample.xml").readText()
    val result = routes.responseToXml(xml)

    expect(result).toContain("FTI010GetCmdsResponse")
    expect(result).toContain("<GrossWeightMeasure")
  }
}
