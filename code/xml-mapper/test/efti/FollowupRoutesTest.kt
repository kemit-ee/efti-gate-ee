package efti

import ch.tutteli.atrium.api.fluent.en_GB.toContain
import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import efti.domain.GateId
import efti.domain.PlatformId
import efti.domain.UIL
import io.mockk.verify
import klite.uuid
import org.junit.jupiter.api.Test
import java.io.File

class FollowupRoutesTest : BaseMocks() {
  val routes = FollowupRoutes(requestIdHandler)

  @Test fun requestToJson() {
    val xml = File("xsd/FTI025/sample.xml").readText()
    val result = routes.requestToJson(xml, exchange)

    verify { requestIdHandler.send(exchange, "17022113-89b5-11f1-bec0-3c9c0f2eb459".uuid) }

    expect(result.uil.gateId).toEqual(GateId("Gate-001"))
    expect(result.uil.platformId).toEqual(PlatformId("Platform-001"))
    expect(result.uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
    expect(result.message).toEqual("Follow-up: correction of consignee address")
  }

  @Test fun requestToXml() {
    val uil = UIL(PlatformId("Platform-001"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("Gate-001"))
    val request = FollowupRequest(uil, "Follow-up: correction of consignee address")
    val xml = routes.requestToXml(request, exchange)

    expect(xml).toContain("<TypeCode>025</TypeCode>")
    expect(xml).toContain("<GateID>Gate-001</GateID>")
    expect(xml).toContain("<PlatformID>Platform-001</PlatformID>")
    expect(xml).toContain("550e8400-e29b-41d4-a716-446655440000")
    expect(xml).toContain("Follow-up: correction of consignee address")
  }

  @Test fun requestToXmlRoundtrip() {
    val uil = UIL(PlatformId("Platform-001"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("Gate-001"))
    val request = FollowupRequest(uil, "Follow-up: correction of consignee address")
    val xml = routes.requestToXml(request, exchange)
    val parsed = routes.requestToJson(xml, exchange)

    verify { requestIdHandler.send(exchange, "00000000-0000-0000-0000-000000000001".uuid) }

    expect(parsed.uil.gateId).toEqual(GateId("Gate-001"))
    expect(parsed.uil.platformId).toEqual(PlatformId("Platform-001"))
    expect(parsed.uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
    expect(parsed.message).toEqual("Follow-up: correction of consignee address")
  }

  @Test fun responseToJson() {
    val xml = File("xsd/FTI030/sample.xml").readText()
    val result = routes.responseToJson(xml, exchange)

    verify { requestIdHandler.send(exchange, "17022113-89b5-11f1-bec0-3c9c0f2eb459".uuid) }

    expect(result.gateId).toEqual(GateId("Gate-001"))
    expect(result.platformId).toEqual(PlatformId("Platform-001"))
    expect(result.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
  }

  @Test fun responseToXml() {
    val uil = UIL(PlatformId("Platform-001"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("Gate-001"))
    val xml = routes.responseToXml(uil, exchange)

    expect(xml).toContain("<TypeCode>030</TypeCode>")
    expect(xml).toContain("<GateID>Gate-001</GateID>")
    expect(xml).toContain("<PlatformID>Platform-001</PlatformID>")
    expect(xml).toContain("550e8400-e29b-41d4-a716-446655440000")
  }

  @Test fun responseToXmlRoundtrip() {
    val uil = UIL(PlatformId("Platform-001"), "550e8400-e29b-41d4-a716-446655440000".uuid, GateId("Gate-001"))
    val xml = routes.responseToXml(uil, exchange)
    val parsed = routes.responseToJson(xml, exchange)

    verify { requestIdHandler.send(exchange, "00000000-0000-0000-0000-000000000001".uuid) }

    expect(parsed.gateId).toEqual(GateId("Gate-001"))
    expect(parsed.platformId).toEqual(PlatformId("Platform-001"))
    expect(parsed.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
  }
}
