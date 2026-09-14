package efti

import ch.tutteli.atrium.api.fluent.en_GB.notToContain
import ch.tutteli.atrium.api.fluent.en_GB.notToEqualNull
import ch.tutteli.atrium.api.fluent.en_GB.toBeEmpty
import ch.tutteli.atrium.api.fluent.en_GB.toContain
import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.fluent.en_GB.toThrow
import ch.tutteli.atrium.api.verbs.expect
import efti.domain.GateId
import efti.domain.PlatformId
import efti.domain.TransportMeansRow
import efti.subsets.CountryCode.DE
import klite.json.JsonMapper
import klite.json.parse
import klite.uuid
import org.junit.jupiter.api.Test

class TransportMeansRoutesTest {
  val routes = TransportMeansRoutes()
  val json = JsonMapper()

  // Raw local get_consignments.sql row (local-hit shape): camelCased by ReSql, carries rowId, the
  // identifier xml blob and the equipment seq arrays — all of which the projection must drop.
  val localRowJson = """[{
    "rowId": 42,
    "datasetId": "550e8400-e29b-41d4-a716-446655440000",
    "platformId": "Platform-001",
    "gateId": "EU-EE",
    "xml": "<ParameterIDSetCriteria/>",
    "status": "ACTIVE",
    "transportMode": "1",
    "mainTransportId": "VESSEL-001",
    "mainTransportType": "IMO",
    "transportRegCountry": "DE",
    "usedEquipmentIds": ["TE-001", "TE-002"],
    "usedEquipmentSeq": [1, 2],
    "carriedEquipmentIds": ["TE-003"],
    "carriedEquipmentSeq": [1],
    "acceptanceDate": "2021-09-24T08:50:00Z",
    "createdAt": "2026-09-01T12:00:00Z"
  }]"""

  // Remote ConsignmentRow (broadcast shape): flat uil fields, xml, no createdAt, null status —
  // always sparse compared to a local row.
  val remoteRowJson = """[{
    "datasetId": "550e8400-e29b-41d4-a716-446655440001",
    "platformId": "Platform-002",
    "gateId": "EU-FI",
    "xml": "<ParameterIDSetCriteria/>",
    "mainTransportId": "VESSEL-001",
    "usedEquipmentIds": [],
    "usedEquipmentSeq": [],
    "status": null
  }]"""

  @Test fun localRowsNormalizeToCuratedProjection() {
    val result = routes.normalized(json.parse<List<TransportMeansRow>>(localRowJson))

    expect(result.size).toEqual(1)
    val c = result.first()
    expect(c.uil.gateId).toEqual(GateId("EU-EE"))
    expect(c.uil.platformId).toEqual(PlatformId("Platform-001"))
    expect(c.uil.datasetId).toEqual("550e8400-e29b-41d4-a716-446655440000".uuid)
    expect(c.mainTransportId).toEqual("VESSEL-001")
    expect(c.transportRegCountry).toEqual(DE)
    expect(c.usedEquipmentIds!!).toContain("TE-001", "TE-002")
    expect(c.status).toEqual("ACTIVE")
    expect(c.createdAt).notToEqualNull()
  }

  @Test fun remoteRowsNormalizeWithoutCreatedAt() {
    val result = routes.normalized(json.parse<List<TransportMeansRow>>(remoteRowJson))

    expect(result.size).toEqual(1)
    val c = result.first()
    expect(c.uil.gateId).toEqual(GateId("EU-FI"))
    expect(c.createdAt).toEqual(null)
    expect(c.status).toEqual(null)
  }

  // The route must render nulls explicitly: sparse remote rows would otherwise emit fewer keys
  // than local rows, breaking ADR-007's identical-shape promise.
  @Test fun sparseRowsRenderTheFullKeySet() {
    val rendered = routes.renderNormalized(json.parse<List<TransportMeansRow>>(remoteRowJson))

    expect(rendered).toContain("\"createdAt\":null")
    expect(rendered).toContain("\"acceptanceDate\":null")
    expect(rendered).toContain("\"dangerousGoods\":null")
    expect(rendered).toContain("\"uil\":")
  }

  @Test fun xmlBlobAndSeqArraysAreDropped() {
    val rendered = routes.renderNormalized(json.parse<List<TransportMeansRow>>(localRowJson))

    expect(rendered).notToContain("\"xml\"")
    expect(rendered).notToContain("Seq")
    expect(rendered).notToContain("rowId")
    expect(rendered).toContain("\"uil\":")
  }

  @Test fun emptyInputIsEmptyProjection() {
    expect(routes.normalized(json.parse<List<TransportMeansRow>>("[]"))).toBeEmpty()
  }

  // A row without the uil fields cannot be projected — the DSL's check_normalize treats the
  // resulting non-200 as normalize_failed (502) rather than emitting a broken consignment.
  @Test fun rowWithoutUilFieldsIsRejected() {
    expect {
      json.parse<List<TransportMeansRow>>("""[{ "mainTransportId": "VESSEL-001" }]""")
    }.toThrow<Exception>()
  }
}
