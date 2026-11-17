# Search criteria

One criteria object is used in three places:

| Where | How it arrives |
|---|---|
| X-Road [`search`](../api/authorities/search.md) | the request body is the criteria object |
| Core authority search `POST /efti/api/v1/authority/search` ([Core authority API](../api/internal/core-authority.md)) | the same body, forwarded verbatim by the X-Road route |
| FTI019 between gates ([Identifier search: FTI019 / FTI021](../api/gates/search-fti019.md)) | xml-mapper turns the object into `ParameterSearchCriteria` XML; the receiving gate turns it back |

The local match is done by `DSL/Resql/efti/POST/get_consignments.sql`. The XML mapping is
`code/xml-mapper/src/efti/xml/fti/ParameterSearchCriteria.kt` behind
`POST /api/v1/search/request-to-xml` and `/request-to-json` (`SearchRoutes.kt`).

## Shape

Each key is a field. Its value is an object with `operator` and one value key. Date fields take an
array of up to two such objects. All fields present are ANDed. An empty object `{}` matches
everything.

```json
{
  "mainTransportId": {"operator": "EQ", "id": "TST-001"},
  "transportMode":   {"operator": "EQ", "mode": "3"},
  "loadingDate": [
    {"operator": "GE", "date": "2026-11-01T00:00:00Z"},
    {"operator": "LT", "date": "2026-11-02T00:00:00Z"}
  ]
}
```

## Operators

| Field kind | First element / single object | Second date element |
|---|---|---|
| text, code, country, id, sequence | `EQ`, `NE` | — |
| date | `EQ`, `NE`, `LT`, `LE`, `GT`, `GE` | `LT`, `LE`, `GT`, `GE` |

- Operators are upper case. Locally, any other value (`eq`, `LT` on a text field, a missing
  `operator`) matches **no row**. It is not an error.
- On array columns (equipment), `EQ` means "the array contains the value" and `NE` "does not
  contain it"; a row with no equipment counts as an empty array, so it matches `NE`.
- On single-value columns, a row whose column is `NULL` matches neither `EQ` nor `NE`.
- Matching is exact and case-sensitive (`tst-001` does not find `TST-001`).

## Fields

| Field | Value key | Type | DB column (`consignments`) | FTI019 element in `ParameterSearchCriteria` | XML value element |
|---|---|---|---|---|---|
| `mainTransportId` | `id` | string | `main_transport_id` | `MainCarriageTransportMeansIDParameterScope` | `ID` |
| `mainTransportType` | `code` | string | `main_transport_type` | `MainCarriageTransportMeansTypeCodeParameterScope` | `TransportMeansParameterCode` |
| `transportMode` | `mode` | mode code `1` sea, `2` rail, `3` road, `4` air, `8` inland waterway | `transport_mode` | `MainCarriageModeCodeParameterScope` | `TransportModeParameterCode` |
| `transportRegCountry` | `country` | ISO country code | `transport_reg_country` | `TransportMeansRegistrationCountryParameterScope` | `CountryID` |
| `dangerousGoodsCode` | `code` | `0`, `1`, `1a`, `2`, `3`, `4` (gate indicator, not the ADR class) | `dangerous_goods` | `DangerousGoodsIndicationCodeParameterScope` | `DangerousGoodsIndicationParameterCode` |
| `acceptanceCountry` | `country` | country | `acceptance_country` | `CarrierAcceptanceCountryParameterScope` | `CountryID` |
| `deliveryCountry` | `country` | country | `delivery_country` | `DeliveryCountryParameterScope` | `CountryID` |
| `loadingCountry` | `country` | country | `loading_country` | `MainCarriageLoadingCountryParameterScope` | `CountryID` |
| `unloadingCountry` | `country` | country | `unloading_country` | `MainCarriageUnloadingCountryParameterScope` | `CountryID` |
| `usedEquipmentId` | `id` | string | `used_equipment_ids` (array) | `UsedTransportEquipmentIDParameterScope` | `ID` |
| `usedEquipmentCategory` | `code` | string | `used_equipment_categories` (array) | `UsedTransportEquipmentCategoryCodeParameterScope` | `TransportEquipmentCategoryParameterCode` |
| `usedEquipmentCountry` | `country` | country | `used_equipment_countries` (array) | `UsedTransportEquipmentRegistrationCountryParameterScope` | `CountryID` |
| `usedEquipmentSeq` | `sequence` | integer | `used_equipment_seq` (array) | `UsedTransportEquipmentSequenceNumberParameterScope` | `SequenceNumeric` |
| `carriedEquipmentId` | `id` | string | `carried_equipment_ids` (array) | `CarriedTransportEquipmentIDParameterScope` | `ID` |
| `carriedEquipmentCategory` | `code` | string | `carried_equipment_categories` (array) | `CarriedTransportEquipmentCategoryCodeParameterScope` | `TransportEquipmentCategoryParameterCode` |
| `carriedEquipmentSeq` | `sequence` | integer | `carried_equipment_seq` (array) | `CarriedTransportEquipmentSequenceNumberParameterScope` | `SequenceNumeric` |
| `acceptanceDate` | `date` (array of 1–2) | ISO 8601 date-time | `acceptance_date` | `CarrierAcceptanceDateParameterScope` | `SpecifiedDateTime/udt:DateTimeString` |
| `deliveryDate` | `date` (array) | date-time | `delivery_date` | `DeliveryDateParameterScope` | same |
| `loadingDate` | `date` (array) | date-time | `loading_date` | `MainCarriageLoadingDateParameterScope` | same |
| `unloadingDate` | `date` (array) | date-time | `unloading_date` | `MainCarriageUnloadingDateParameterScope` | same |
| `transportMeansOrEquipmentId` | `id` | string | `main_transport_id` OR `used_equipment_ids` OR `carried_equipment_ids` | sent as `MainCarriageTransportMeansIDParameterScope` (see below) | `ID` |
| `status` | `status` | `ACTIVE`, `INACTIVE` | `status` | not sent | — |

Each scope element in the XML carries the operator as `<SubtypeCode>`. There is no
`carriedEquipmentCountry`.

### Gate-only fields

- `transportMeansOrEquipmentId` is an OR across the three identifier families, used by
  [`transport-means`](../api/authorities/transport-means.md) with `scope: allgates`. FTI019 has no
  OR criterion, so xml-mapper sends it as `MainCarriageTransportMeansIDParameterScope`. Peer
  gates therefore match it against the main transport means only; an equipment id matches
  locally but not remotely. If `mainTransportId` is also given, the OR criterion is left out of
  the XML (one tag only).
- `status` narrows to one lifecycle status. FTI019 has no status element, so it is dropped from
  the XML; peer gates return what their own search returns.
- Without `status`, the local search returns every consignment that is not `DELETED`, so
  `INACTIVE` rows are included. `transport-means` always adds `status EQ ACTIVE`.

### What the search returns

- Only the latest version of each `(platformId, datasetId)` is matched.
- Newest first (`created_at`, then `revision`).
- At most 20 rows. The criteria object has no paging; `limit`/`offset` are parameters of the SQL
  that the authority routes do not pass, so the ReSql default of 20 applies.

## Examples

All requests go to the X-Road `search` on G1 with the dev client
`DEV/GOV/70000099/testSubsystem` (public in the repo). Each one pipes the answer through a
one-liner that prints `gateId datasetId mainTransportId` per row. The local stack also holds rows
uploaded by other tests, so extra rows may appear on yours.

```bash
search() {
  curl -s -X POST http://127.0.0.1:27087/xroad/provide/v1/search \
    -H 'Content-Type: application/json' \
    -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
    -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" -d "$1" \
  | python3 -c 'import json,sys; [print(r["uil"]["gateId"], r["uil"]["datasetId"], r["mainTransportId"]) for r in json.load(sys.stdin)]'
}
```

### A date range

```bash
search '{"acceptanceDate":[{"operator":"GE","date":"2026-11-01T00:00:00Z"},{"operator":"LT","date":"2026-11-02T00:00:00Z"}]}'
```

```text
G1 11111111-0000-4000-8000-000000000004 OH-LKA
G1 11111111-0000-4000-8000-000000000002 TST-002
G1 11111111-0000-4000-8000-000000000001 TST-001
```

### Equipment

```bash
search '{"usedEquipmentId":{"operator":"EQ","id":"CONT-100"}}'
search '{"carriedEquipmentSeq":{"operator":"EQ","sequence":2}}'
search '{"transportMeansOrEquipmentId":{"operator":"EQ","id":"CONT-200"}}'
```

```text
G1 11111111-0000-4000-8000-000000000002 TST-002
G1 11111111-0000-4000-8000-000000000003 IMO9321483
G1 11111111-0000-4000-8000-000000000003 IMO9321483
```

### NE combined with EQ

Road consignments with an acceptance country other than `EE`:

```bash
search '{"acceptanceCountry":{"operator":"NE","country":"EE"},"transportMode":{"operator":"EQ","mode":"3"}}'
```

```text
G1 803dab72-dc75-4879-8386-50127588196b MOCK-456
G1 11111111-0000-4000-8000-000000000002 TST-002
```

(`MOCK-456` was uploaded by another test on this stack.)

### Codes and countries

```bash
search '{"transportRegCountry":{"operator":"EQ","country":"FI"},"status":{"operator":"EQ","status":"ACTIVE"}}'
search '{"mainTransportType":{"operator":"EQ","code":"31"}}'
search '{"dangerousGoodsCode":{"operator":"EQ","code":"1"}}'
```

```text
G1 11111111-0000-4000-8000-000000000004 OH-LKA
G1 11111111-0000-4000-8000-000000000003 IMO9321483
G1 11111111-0000-4000-8000-000000000005 TST-001
G1 11111111-0000-4000-8000-000000000002 TST-002
G1 11111111-0000-4000-8000-000000000001 TST-001
G1 11111111-0000-4000-8000-000000000001 TST-001
```

### The FTI019 rendering

What xml-mapper makes of a criteria object (gate-internal call, dev token public in the repo):

```bash
curl -s -X POST http://127.0.0.1:27082/api/v1/search/request-to-xml \
  -H 'Content-Type: application/json' \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H 'x-request-id: 0a11ce5e-0001-4d1d-8f1a-0ec100000001' \
  -d '{"transportMeansOrEquipmentId":{"operator":"EQ","id":"G2-AB123"},
       "transportRegCountry":{"operator":"EQ","country":"LV"},
       "status":{"operator":"EQ","status":"ACTIVE"},
       "usedEquipmentSeq":{"operator":"NE","sequence":2},
       "transportMode":{"operator":"EQ","mode":"3"},
       "acceptanceDate":[{"operator":"GE","date":{"formatId":"102","value":"20261101"}}]}'
```

```xml
<rsm:FTI019SearchIdentifierRequest …>…
    <rsm:EFTIIDInformation><ParameterSearchCriteria><CarrierAcceptanceDateParameterScope><SubtypeCode>GE</SubtypeCode><SpecifiedDateTime><udt:DateTimeString format="102">20261101</udt:DateTimeString></SpecifiedDateTime></CarrierAcceptanceDateParameterScope><MainCarriageTransportMeansIDParameterScope><SubtypeCode>EQ</SubtypeCode><ID>G2-AB123</ID></MainCarriageTransportMeansIDParameterScope><MainCarriageModeCodeParameterScope><SubtypeCode>EQ</SubtypeCode><TransportModeParameterCode>3</TransportModeParameterCode></MainCarriageModeCodeParameterScope><TransportMeansRegistrationCountryParameterScope><SubtypeCode>EQ</SubtypeCode><CountryID>LV</CountryID></TransportMeansRegistrationCountryParameterScope><UsedTransportEquipmentSequenceNumberParameterScope><SubtypeCode>NE</SubtypeCode><SequenceNumeric>2</SequenceNumeric></UsedTransportEquipmentSequenceNumberParameterScope></ParameterSearchCriteria></rsm:EFTIIDInformation>
  </rsm:FTI019SearchIdentifierRequest>
```

`transportMeansOrEquipmentId` became `MainCarriageTransportMeansIDParameterScope`; `status`
disappeared. The receiving gate's `request-to-json` turns this XML back into
`{"acceptanceDate":[{"date":"2026-11-01T00:00:00Z","operator":"GE"}], "mainTransportId":{"id":"G2-AB123","operator":"EQ"}, …}`
(run against G2's xml-mapper, `127.0.0.1:28082`).

## Pitfalls

These were all run against G1. They return `200` without an error, so a caller does not see
that the criterion was not applied.

| Input | Local result | Cross-gate result |
|---|---|---|
| unknown field, e.g. `{"plate":{…}}` | ignored: the search matches everything (20 rows) | — (local hit) |
| a date field as an object instead of an array | ignored: matches everything | — |
| wrong value key, e.g. `mainTransportId` with `code` | matches nothing | xml-mapper rejects it (`missing id`); the fan-out sends no valid FTI019; the poll returns `[]` |
| `LT`/`LE`/`GT`/`GE` on a text field | matches nothing | xml-mapper rejects the operator; the poll returns `[]` |
| lower-case operator `eq` | matches nothing | xml-mapper accepts it as `EQ`, so peer rows can come back |
| unknown country code, e.g. `XX` | matches nothing | xml-mapper rejects it (`No class CountryCode constant: XX`); the poll returns `[]` |
| a date as an ISO string (the documented form) | works | xml-mapper cannot convert a string to `DateTimeString`; the poll returns `[]` |
| a date as `{"formatId","value"}` (what xml-mapper wants) | `502`: the SQL cannot cast it | — |
| `"date": "yesterday"` | accepted by PostgreSQL as a timestamp literal | — |
| `usedEquipmentSeq` with a non-integer `sequence` | `502`: the SQL cast fails | — |

So date criteria only work on the local slice. On a local miss they never reach the other gates.
When xml-mapper rejects the criteria, core's `authority/search.yml` does not check the status of
`criteria_to_xml` and broadcasts the error body; the peer answers `EBMS:0002 UNSUPPORTED_MESSAGE`
and the search completes with no rows.

The proof: `G2-AB123` is only at G2. Asked at G1 without a date, the poll returns the
G2 row ([search](../api/authorities/search.md)). With a date that the row satisfies:

```bash
SID=$(cat /proc/sys/kernel/random/uuid)
curl -si -X POST http://127.0.0.1:27087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $SID" \
  -d '{"mainTransportId":{"operator":"EQ","id":"G2-AB123"},"acceptanceDate":[{"operator":"GE","date":"2026-01-01T00:00:00Z"}]}'
# x-poll-more: true, body []
curl -si -X POST http://127.0.0.1:27087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d "{\"poll\":true,\"searchId\":\"$SID\"}"
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

[]
```

The xml-mapper log for that request id shows
`Cannot get criteria: No known converter from String to class efti.xml.fti.DateTimeString`, and the
edelivery log `eDelivery request failed with status 500: EBMS:0002 - UNSUPPORTED_MESSAGE`.

## Notes

- The OpenAPI document (`SearchCriteria`, `StringCriterion`) lists `LT`/`LE`/`GT`/`GE` for every
  field and mode codes `1`…`5`. The SQL evaluates only `EQ`/`NE` outside dates, and the mode codes
  in use are `1`, `2`, `3`, `4`, `8`. The integration guide does not list
  `transportMeansOrEquipmentId`, `status`, `usedEquipmentSeq` and `carriedEquipmentSeq`; the SQL
  accepts all four.
- The developer mock filters its four canned rows with the same rules
  ([Developer mock](../api/authorities/developer-mock.md)).
