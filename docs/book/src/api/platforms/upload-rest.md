# Upload identifiers (REST)

A platform registers a consignment at its gate: the UIL plus the search criteria an authority can
search for. The gate stores one identifier row. It does not store the dataset itself.

`POST /platforms/v1/consignments/{datasetId}` · `POST /platforms/v1/consignments` · on ruuter
(G1 `:27086`, G2 `:28086`) · `X-Api-Key` of an `ONLINE` platform · source
`DSL/Ruuter/platforms/POST/v1/consignments.yml`, guard `DSL/Ruuter/platforms/.guard.yml`, mapper
`code/xml-mapper/src/efti/UploadRoutes.kt`

Two forms, same route file:

| Form | Path | Body |
|---|---|---|
| Short | `/platforms/v1/consignments/{datasetId}` | a bare `<ParameterIDSetCriteria>` element |
| Full | `/platforms/v1/consignments` | a complete `FTI004UploadIdentifierRequest` (GateID, PlatformID, DatasetID, ParameterIDSetCriteria) |

The examples use the dev key `mock-secret-key` of platform `mock`. It is public in the repository.

## Request

| Header | Required | Value |
|---|---|---|
| `X-Api-Key` | yes | The platform's `apiKey` from `registry/platforms/<id>.yml` |
| `Content-Type` | yes | `text/xml` (raw XML body), or `application/json` with `{"xml": "<…>"}`. `application/xml` and `text/plain` are answered `400 Field missing: xml`. |
| `X-Request-Id` | no | Correlation id, passed on to xml-mapper. Not used for de-duplication: the same id twice gives two rows. |

| Path parameter | Required | Type | Meaning |
|---|---|---|---|
| `datasetId` | short form: yes; full form: ignored | UUID | The dataset id of the UIL. Not a UUID: `400`. Missing in the short form: `502` (see Responses). |

How the gate tells the forms apart: if the body contains the text `FTI004UploadIdentifierRequest`
anywhere, it is parsed as a full FTI004. Otherwise it is parsed as a bare `ParameterIDSetCriteria`.

Short form, where the UIL comes from:

| UIL part | Taken from |
|---|---|
| `gateId` | the gate's own id (`OWN_GATE_ID`). An `X-Gate-Id` request header is ignored. |
| `platformId` | the authenticated platform |
| `datasetId` | the path parameter |

Full form: the UIL is read from `rsm:EFTIIDInformation/UniqueIDSetUniqueIDSet` (`GateID`,
`PlatformID`, `DatasetID`). `GateID` must be this gate and `PlatformID` the authenticated platform
(case-insensitive), otherwise `403`. `ExchangedDocument` is read, but only its query id is used (as
a log correlation id).

### ParameterIDSetCriteria

All elements are optional (`minOccurs="0"` in `code/xml-mapper/xsd/FTI004`). An empty
`<ParameterIDSetCriteria/>` is accepted and stores a row with no criteria. Elements are matched by
local name; namespaces are not checked. Unknown elements are ignored. The body is **not** validated
against the XSD.

| Element (under `ParameterIDSetCriteria`) | Repeats | Column | Read as / validation |
|---|---|---|---|
| `CarrierAcceptanceDateParameterScope/SpecifiedDateTime/DateTimeString` | no | `acceptance_date` | Date, see formats below |
| `CarrierAcceptanceCountryParameterScope/CountryID` | no | `acceptance_country` | Country code |
| `DeliveryDateParameterScope/SpecifiedDateTime/DateTimeString` | no | `delivery_date` | Date |
| `DeliveryCountryParameterScope/CountryID` | no | `delivery_country` | Country code |
| `DangerousGoodsIndicationCodeParameterScope/DangerousGoodsIndicationParameterCode` | no | `dangerous_goods` | Any text up to 2 characters (`0`, `1`, `1a`, `2`, `3`, `4` are the known codes). Longer: `500`. |
| `MainCarriageModeCodeParameterScope/TransportModeParameterCode` | no | `transport_mode` | Any single character (`1` sea, `2` rail, `3` road, `4` air, `8` inland waterway). Not checked against the list. Longer: `500`. |
| `MainCarriageTransportMeansIDParameterScope/ID` | no | `main_transport_id` | Text (licence plate, IMO number, …) |
| `MainCarriageTransportMeansTypeCodeParameterScope/TransportMeansParameterCode` | no | `main_transport_type` | Text |
| `TransportMeansRegistrationCountryParameterScope/CountryID` | no | `transport_reg_country` | Country code |
| `MainCarriageLoadingDateParameterScope/SpecifiedDateTime/DateTimeString` | no | `loading_date` | Date |
| `MainCarriageLoadingCountryParameterScope/CountryID` | no | `loading_country` | Country code |
| `MainCarriageUnloadingDateParameterScope/SpecifiedDateTime/DateTimeString` | no | `unloading_date` | Date |
| `MainCarriageUnloadingCountryParameterScope/CountryID` | no | `unloading_country` | Country code |
| `UsedTransportEquipmentIDParameterScope/ID` | yes | `used_equipment_ids` | Text array |
| `UsedTransportEquipmentCategoryCodeParameterScope/TransportEquipmentCategoryParameterCode` | yes | `used_equipment_categories` | Text array |
| `UsedTransportEquipmentRegistrationCountryParameterScope/CountryID` | yes | `used_equipment_countries` | Country code array |
| `UsedTransportEquipmentSequenceNumberParameterScope/SequenceNumeric` | yes | `used_equipment_seq` | Integer array. Not an integer: `400`. |
| `CarriedTransportEquipmentIDParameterScope/ID` | yes | `carried_equipment_ids` | Text array |
| `CarriedTransportEquipmentCategoryCodeParameterScope/TransportEquipmentCategoryParameterCode` | yes | `carried_equipment_categories` | Text array |
| `CarriedTransportEquipmentSequenceNumberParameterScope/SequenceNumeric` | yes | `carried_equipment_seq` | Integer array |

Repeated values are stored in document order, so the n-th `ID`, category, country and sequence
number belong together.

**Country codes** are checked against the mapper's list (`code/xml-mapper/src/efti/subsets/CountryCode.kt`:
ISO 3166-1 alpha-2 codes of Europe and beyond, plus `EU`). Lower case is accepted and stored in
upper case. An unknown code (`XX`) is `400`.

**Dates**: `<udt:DateTimeString format="…">`. Supported `format` values:

| `format` | Pattern | Example |
|---|---|---|
| `102` | `CCYYMMDD` | `20260924` |
| `203` | `CCYYMMDDHHMM` | `202609240730` |
| `204` | `CCYYMMDDHHMMSS` | `20260924073000` |
| `205` | `CCYYMMDDHHMMZHHMM` | `202609240730+0300` |
| `207` (default if `format` is missing) | `CCYYMMDDHHMMSSZHHMM` | `20260924073000+0300` |

Values without an offset are read as UTC. Another `format` code, or a value that does not match
the pattern, makes the mapper fail with `500`, which the gate answers as `502` (see Notes).

### What is stored

One row is appended to `consignments` per successful upload:

| Column | Value |
|---|---|
| `row_id` | new UUID (returned as `rowId`) |
| `dataset_id`, `platform_id`, `gate_id` | the UIL, as written in the upload (case kept; the columns are case-insensitive) |
| `xml` | the `ParameterIDSetCriteria` element as text (full form: cut out of the FTI004) |
| `status` | always `ACTIVE`. The upload cannot set another status. |
| criteria columns | see the table above; missing elements are `NULL`, missing lists are `{}` |
| `created_at`, `revision` | insert time and a sequence number; together they order the versions |

## Responses

| Status | When | Body |
|---|---|---|
| `201` | Stored | JSON: `rowId`, `datasetId`, `platformId`, `gateId`, `status` (`ACTIVE`), `createdAt` (the latest version of this dataset, read back after the insert) |
| `400` | Body missing, or wrong `Content-Type` | `{"error":"Field missing: xml"}` (Ruuter body allowlist) |
| `400` | xml-mapper rejected a value: path `datasetId` not a UUID, unknown country code, non-integer `SequenceNumeric`, full-form `DatasetID` not a UUID | `{"error":"Invalid consignment XML","status":400}` |
| `401` | `X-Api-Key` missing or not the key of an `ONLINE` platform; registry not reachable | RFC 7807, `type` `…/errors/unauthorized` |
| `403` | Key matches more than one `ONLINE` platform | RFC 7807, `type` `…/errors/forbidden-multi-platform` |
| `403` | Full form: `GateID` is not this gate, or `PlatformID` is not the authenticated platform | `{"error":"Consignment UIL must belong to the authenticated platform and this gate","status":403}` |
| `405` | Method other than `POST` | `{"allow":["POST"],"error":"Method Not Allowed"}` |
| `500` | ReSql insert failed (e.g. a mode code longer than 1 character, a dangerous-goods code longer than 2) | `{"error":"Failed to insert consignment","status":500}` |
| `500` | Inserted, but the read-back found nothing | `{"error":"Consignment inserted but could not be verified","status":500}` |
| `502` | xml-mapper not reachable, **or** xml-mapper answered `5xx`: malformed XML, unsupported date format, short form without `datasetId` in the path | `{"error":"XML mapper unavailable","status":502}` |

The error bodies of the route itself are plain JSON (`content-type: application/json`), not
RFC 7807. Only the guard answers RFC 7807.

## Examples

### Short form

```bash
curl -i -X POST "http://127.0.0.1:27086/platforms/v1/consignments/$(cat /proc/sys/kernel/random/uuid)" \
  -H 'Content-Type: text/xml' -H 'X-Api-Key: mock-secret-key' \
  --data-binary @docs/developer/examples/platforms/short-upload.xml
```

```http
HTTP/1.1 201 Created
content-type: application/json
content-length: 193
{"createdAt":"2026-11-03T16:01:34.661681Z","datasetId":"803dab72-dc75-4879-8386-50127588196b","gateId":"G1","platformId":"mock","rowId":"135a4065-4bb6-44e3-8d75-a7b7605fc495","status":"ACTIVE"}
```

The stored row (`docker exec efti-g1-database psql -U efti efti -x -c "select … from consignments where dataset_id='803dab72-…'"`):

```text
platform_id                  | mock
gate_id                      | G1
status                       | ACTIVE
transport_mode               | 3
acceptance_date              | 2026-09-24 00:00:00+00
acceptance_country           | DE
main_transport_id            | MOCK-123
used_equipment_ids           | {}
…
revision                     | 27
```

### Full FTI004

`docs/developer/examples/platforms/full-upload.xml` fills every criterion. It has a fixed
`DatasetID`; replace it with a fresh UUID:

```bash
sed -e "s/550e8400-e29b-41d4-a716-446655440000/$(cat /proc/sys/kernel/random/uuid)/" \
  docs/developer/examples/platforms/full-upload.xml > /tmp/full.xml
curl -i -X POST http://127.0.0.1:27086/platforms/v1/consignments \
  -H 'Content-Type: text/xml' -H 'X-Api-Key: mock-secret-key' --data-binary @/tmp/full.xml
```

```http
HTTP/1.1 201 Created
content-type: application/json
content-length: 193
{"createdAt":"2026-11-03T16:01:47.236241Z","datasetId":"1f86eecd-dec7-4644-8531-649682b1157c","gateId":"G1","platformId":"mock","rowId":"5ec1636b-3a6c-4c3e-9516-aa0b561916df","status":"ACTIVE"}
```

Stored criteria:

```text
transport_mode               | 1
acceptance_date              | 2021-09-24 00:00:00+00
acceptance_country           | DE
delivery_date                | 2026-09-24 00:00:00+00
delivery_country             | EE
dangerous_goods              | 2
main_transport_id            | VESSEL-001
main_transport_type          | 1513
transport_reg_country        | DE
loading_date                 | 2021-09-24 00:00:00+00
loading_country              | FI
unloading_date               | 2021-09-24 00:00:00+00
unloading_country            | AE
used_equipment_ids           | {TE-001,TE-002}
used_equipment_categories    | {T10,T10}
used_equipment_countries     | {DE,DE}
used_equipment_seq           | {1,2}
carried_equipment_ids        | {TE-003,TE-004}
carried_equipment_categories | {BPR,BPR}
carried_equipment_seq        | {3,4}
```

### JSON-wrapped body

```bash
curl -i -X POST "http://127.0.0.1:27086/platforms/v1/consignments/$(cat /proc/sys/kernel/random/uuid)" \
  -H 'Content-Type: application/json' -H 'X-Api-Key: mock-secret-key' \
  -d '{"xml":"<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>DOC-B-CT</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>"}'
```

```http
HTTP/1.1 201 Created

{"createdAt":"2026-11-03T16:08:38.214798Z","datasetId":"6d474e98-699c-4594-98ec-bb99ff13697c","gateId":"G1","platformId":"mock","rowId":"3ceb0434-a2cd-41b8-b319-df753bc2eefe","status":"ACTIVE"}
```

### A new version of the same dataset

Send the same `datasetId` again. The gate appends a new row; it never updates or deletes one.

```bash
curl -i -X POST http://127.0.0.1:27086/platforms/v1/consignments/803dab72-dc75-4879-8386-50127588196b \
  -H 'Content-Type: text/xml' -H 'X-Api-Key: mock-secret-key' --data-binary '<ParameterIDSetCriteria xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
  <CarrierAcceptanceDateParameterScope>
    <SpecifiedDateTime><udt:DateTimeString format="102">20260924</udt:DateTimeString></SpecifiedDateTime>
  </CarrierAcceptanceDateParameterScope>
  <CarrierAcceptanceCountryParameterScope><CountryID>DE</CountryID></CarrierAcceptanceCountryParameterScope>
  <MainCarriageTransportMeansIDParameterScope><ID>MOCK-456</ID></MainCarriageTransportMeansIDParameterScope>
  <MainCarriageModeCodeParameterScope><TransportModeParameterCode>3</TransportModeParameterCode></MainCarriageModeCodeParameterScope>
</ParameterIDSetCriteria>'
```

```http
HTTP/1.1 201 Created

{"createdAt":"2026-11-03T16:02:57.379687Z","datasetId":"803dab72-dc75-4879-8386-50127588196b","gateId":"G1","platformId":"mock","rowId":"874e84c9-f7b9-4ea2-821b-713f0d77fc2b","status":"ACTIVE"}
```

```text
                row_id                | status | main_transport_id |          created_at           | revision
--------------------------------------+--------+-------------------+-------------------------------+----------
 135a4065-4bb6-44e3-8d75-a7b7605fc495 | ACTIVE | MOCK-123          | 2026-11-03 16:01:34.661681+00 |       27
 874e84c9-f7b9-4ea2-821b-713f0d77fc2b | ACTIVE | MOCK-456          | 2026-11-03 16:02:57.379687+00 |       43
```

The latest row (`created_at DESC, revision DESC`) is the current state. Search uses only it: after
this call a search for `MOCK-456` finds the dataset and a search for `MOCK-123` does not (shown in
[the use case](../../use-cases/platform-registers-consignment.md)). A new version replaces all
criteria; send the complete set every time.

### No key, wrong key

```bash
curl -i -X POST http://127.0.0.1:27086/platforms/v1/consignments -H 'Content-Type: text/xml' --data-binary '<x/>'
curl -i -X POST http://127.0.0.1:27086/platforms/v1/consignments -H 'Content-Type: text/xml' -H 'X-Api-Key: wrong' --data-binary '<x/>'
```

Both:

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 159

{"detail":"X-Api-Key is missing or does not match a registered platform.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

### Full FTI004 for another gate or another platform

`full-upload.xml` with `<GateID>G2</GateID>`, sent to G1 (or with
`<PlatformID>mock-edelivery</PlatformID>`, sent with the key of `mock`):

```http
HTTP/1.1 403 Forbidden
content-type: application/json
content-length: 96

{"error":"Consignment UIL must belong to the authenticated platform and this gate","status":403}
```

`<GateID>g1</GateID>` with `<PlatformID>MOCK</PlatformID>` is accepted (`201`, stored as
`g1` / `MOCK`).

### Invalid values

```bash
B=http://127.0.0.1:27086/platforms/v1/consignments
H=(-H 'Content-Type: text/xml' -H 'X-Api-Key: mock-secret-key')
# unknown country code
curl -s -X POST $B/$(cat /proc/sys/kernel/random/uuid) "${H[@]}" --data-binary '<ParameterIDSetCriteria><CarrierAcceptanceCountryParameterScope><CountryID>XX</CountryID></CarrierAcceptanceCountryParameterScope></ParameterIDSetCriteria>'
# datasetId not a UUID
curl -s -X POST $B/not-a-uuid "${H[@]}" --data-binary '<ParameterIDSetCriteria/>'
# sequence number not an integer
curl -s -X POST $B/$(cat /proc/sys/kernel/random/uuid) "${H[@]}" --data-binary '<ParameterIDSetCriteria><UsedTransportEquipmentSequenceNumberParameterScope><SequenceNumeric>x</SequenceNumeric></UsedTransportEquipmentSequenceNumberParameterScope></ParameterIDSetCriteria>'
```

Each:

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 48

{"error":"Invalid consignment XML","status":400}
```

### Malformed XML, unsupported date

```bash
curl -i -X POST $B/$(cat /proc/sys/kernel/random/uuid) "${H[@]}" --data-binary '<ParameterIDSetCriteria><MainCarriage'
curl -s -X POST $B/$(cat /proc/sys/kernel/random/uuid) "${H[@]}" --data-binary '<ParameterIDSetCriteria><CarrierAcceptanceDateParameterScope><SpecifiedDateTime><DateTimeString format="102">2026-09-24</DateTimeString></SpecifiedDateTime></CarrierAcceptanceDateParameterScope></ParameterIDSetCriteria>'
```

Each:

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
content-length: 47

{"error":"XML mapper unavailable","status":502}
```

xml-mapper itself answered `500` with
`"ParseError at [row,col]:[1,38]\nMessage: XML document structures must start and end within the same entity."`
(called directly on `:27082/api/v1/upload/request-to-json`).

### Short form without `datasetId`

```bash
curl -i -X POST http://127.0.0.1:27086/platforms/v1/consignments -H 'Content-Type: text/xml' -H 'X-Api-Key: mock-secret-key' \
  --data-binary '<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>DOC-B-1</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>'
```

```http
HTTP/1.1 502 Bad Gateway

{"error":"XML mapper unavailable","status":502}
```

### Value too long for the column

```bash
curl -s -X POST $B/$(cat /proc/sys/kernel/random/uuid) "${H[@]}" --data-binary '<ParameterIDSetCriteria><MainCarriageModeCodeParameterScope><TransportModeParameterCode>33</TransportModeParameterCode></MainCarriageModeCodeParameterScope></ParameterIDSetCriteria>'
```

```http
HTTP/1.1 500 Internal Server Error

{"error":"Failed to insert consignment","status":500}
```

The same for `<DangerousGoodsIndicationParameterCode>123</…>`.

### Wrong content type, empty body

```bash
curl -s -X POST $B/$(cat /proc/sys/kernel/random/uuid) -H 'Content-Type: application/xml' -H 'X-Api-Key: mock-secret-key' --data-binary '<ParameterIDSetCriteria/>'
curl -s -X POST $B/$(cat /proc/sys/kernel/random/uuid) "${H[@]}" --data-binary ''
```

Each:

```http
HTTP/1.1 400 Bad Request

{"error":"Field missing: xml"}
```

### Not run locally

- `403 forbidden-multi-platform`: the registry build refuses two platforms with the same key.
- `401` for a `DISABLED` platform, and `401` when the registry is down: would need a registry
  rebuild or a stopped container.
- `502` for an unreachable xml-mapper and the two ReSql `500` bodies other than the column-length
  case: would need a stopped container.

## Removing a dataset

A platform cannot deactivate or delete a dataset. Every upload stores `status = ACTIVE`; there is
no element or header for another status. The status changes only through:

- the gate's job [`expire-identifiers`](../operations/jobs.md): it appends an `INACTIVE` copy of
  road consignments whose acceptance date is more than 14 days old;
- an administrator: [`DELETE /admin/v1/consignments/{datasetId}`](../admin/consignments.md)
  appends a `DELETED` row.

A later upload of the same UIL appends a new `ACTIVE` row and makes the dataset searchable again,
also after `DELETED`. Run locally on a test dataset: `ACTIVE` → admin delete (`DELETED`) → upload
again → `ACTIVE`. There is no database rule against it for consignments.

## Notes

- Ownership rule, guard and registry: [Freight platforms](./index.md).
- The route also accepts `X-Internal-Service-Token` + `X-Platform-Id` instead of `X-Api-Key`
  (the guard is shared with [`consignments-xml`](./upload-edelivery.md)).
- No XSD validation. A body with another root element, e.g. `<Hello>…</Hello>`, is stored as
  long as the element names inside match.
- A short-form body that happens to contain the text `FTI004UploadIdentifierRequest` (e.g. in an
  `ID`) is parsed as a full FTI004.
- Doc/DSL differences in `docs/developer/platform_api_integration_guide.md`: it lists an optional
  `X-Gate-Id` header (the DSL ignores it; the gate id is always the gate's own) and `400` for XML
  that does not parse (the DSL answers `502`, because xml-mapper reports parse errors as `500`).
- Postman: folder **5. Platform -> Gate**: "upload - short ParameterIDSetCriteria", "upload - full FTI004",
  "upload - FTI004 for another gate -> 403", "upload - no X-Api-Key -> 401",
  "upload - wrong X-Api-Key -> 401".
