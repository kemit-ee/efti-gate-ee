package followup

import ch.tutteli.atrium.api.fluent.en_GB.toContain
import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import efti.domain.GateId
import efti.domain.PlatformId
import efti.domain.UIL
import efti.xml.fti.FTI025LodgeFollowUpCommRequest
import efti.xml.fti.FTI030LodgeFollowUpCommResponse
import klite.uuid
import org.junit.jupiter.api.Test
import java.io.File

class FollowupRoutesTest {
  val routes = FollowupRoutes()

  @Test fun requestToJson() {
    val xml = File("xsd/Normalized/FTI025/sample.xml").readText()
    val result = routes.requestToJson(xml)

    expect(result.uil.gateId).toEqual(GateId("Gate-001"))
    expect(result.uil.platformId).toEqual(PlatformId("Platform-001"))
    expect(result.uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
    expect(result.message).toEqual("Follow-up: correction of consignee address")
  }

  @Test fun requestToXml() {
    val uil = UIL(PlatformId("Platform-001"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("Gate-001"))
    val request = FollowupRequest(uil, "Follow-up: correction of consignee address")
    val xml = routes.requestToXml(request)

    expect(xml).toContain("<TypeCode>025</TypeCode>")
    expect(xml).toContain("<GateID>Gate-001</GateID>")
    expect(xml).toContain("<PlatformID>Platform-001</PlatformID>")
    expect(xml).toContain("550e8400-e29b-41d4-a716-446655440000")
    expect(xml).toContain("Follow-up: correction of consignee address")
  }

  @Test fun requestToXmlRoundtrip() {
    val uil = UIL(PlatformId("Platform-001"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("Gate-001"))
    val request = FollowupRequest(uil, "Follow-up: correction of consignee address")
    val xml = routes.requestToXml(request)
    val parsed = routes.requestToJson(xml)

    expect(parsed.uil.gateId).toEqual(GateId("Gate-001"))
    expect(parsed.uil.platformId).toEqual(PlatformId("Platform-001"))
    expect(parsed.uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
    expect(parsed.message).toEqual("Follow-up: correction of consignee address")
  }

  @Test fun responseToJson() {
    val xml = File("xsd/Normalized/FTI030/sample.xml").readText()
    val result = routes.responseToJson(xml)

    expect(result.gateId).toEqual(GateId("Gate-001"))
    expect(result.platformId).toEqual(PlatformId("Platform-001"))
    expect(result.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
  }

  @Test fun responseToXml() {
    val uil = UIL(PlatformId("Platform-001"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("Gate-001"))
    val xml = routes.responseToXml(uil)

    expect(xml).toContain("<TypeCode>030</TypeCode>")
    expect(xml).toContain("<GateID>Gate-001</GateID>")
    expect(xml).toContain("<PlatformID>Platform-001</PlatformID>")
    expect(xml).toContain("550e8400-e29b-41d4-a716-446655440000")
  }

  @Test fun responseToXmlRoundtrip() {
    val uil = UIL(PlatformId("Platform-001"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("Gate-001"))
    val xml = routes.responseToXml(uil)
    val parsed = routes.responseToJson(xml)

    expect(parsed.gateId).toEqual(GateId("Gate-001"))
    expect(parsed.platformId).toEqual(PlatformId("Platform-001"))
    expect(parsed.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
  }
}
