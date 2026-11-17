# xml-mapper

xml-mapper converts between the eFTI FTI XML messages and the JSON the DSL works with. It has no
state and no database. Ruuter (core and X-Road) calls it; nothing else does.

Service: xml-mapper, G1 `127.0.0.1:27082`, G2 `:28082`, inside the gate `xml-mapper:8082`.
Source: `code/xml-mapper/src/Launcher.kt` and `code/xml-mapper/src/efti/*Routes.kt`.

## Common request

| Header | Required | Value |
|---|---|---|
| `X-Internal-Service-Token` | yes on `/api/v1/**` | `dev-internal-service-token-change-me` (local; public in the repository). Constant-time compare |
| `X-Request-Id` | recommended | UUID. A `*-to-xml` route writes it into `RequestedSpecifiedQuery/ID`; the logs use it as `trace.id`. Without it a random UUID is used |
| `Content-Type` | yes | `text/xml` (or `application/xml`) for XML input, `application/json` for JSON input |
| `X-Gate-Id`, `X-Platform-Id`, `X-Dataset-Id` | only `upload/request-to-json` with a bare `ParameterIDSetCriteria` | the UIL, since the bare XML has none |

All routes are `POST`. A `*-to-xml` route answers `text/xml`, a `*-to-json` route
`application/json`. A `*-to-json` route that parses a full FTI document answers with an
`X-request-id` header set to the document's `RequestedSpecifiedQuery/ID`.

The mapper does not validate against the XSDs in `code/xml-mapper/xsd/`; it parses into its own
classes. A national subset code such as `EE05b` passes.

## Common errors

| Status | When | Body (klite problem JSON) |
|---|---|---|
| 400 | JSON cannot be mapped (missing field, bad UUID) | `{"detail":"Cannot create UIL from {gateId=G1}: missing platformId",…}` |
| 400 | XML parses but lacks a part | `{"detail":"Cannot create FTI009GetCmdsRequest from {}: missing document",…}` |
| 400 | unsupported `Content-Type` | `{"detail":"Cannot get criteria: Unsupported Content-Type: text/plain",…}` |
| 401 | token missing or wrong | `{"detail":"Missing or invalid X-Internal-Service-Token",…,"status":401,"title":"Unauthorized"}` |
| 404 | unknown path, or `GET` on a route | `{"detail":"/api/v1/search/request-to-xml",…,"status":404,"title":"Not Found"}` |
| 500 | XML is not well-formed | `{"detail":"ParseError at [row,col]:[1,8]\nMessage: XML document structures must start and end within the same entity.",…,"status":500}` |
| 500 | `upload/request-to-json` with a bare criteria document and no `X-*-Id` headers | `{"status":500,"title":"Internal Server Error"}` |

## Routes

| Route | Input → output | Called by |
|---|---|---|
| `upload/request-to-json` | FTI004 or `ParameterIDSetCriteria` XML → `ConsignmentRow` JSON | `platforms/POST/v1/consignments.yml` |
| `upload/response-to-xml` | UIL JSON → FTI029 | `platforms/POST/v1/consignments-xml.yml` |
| `search/request-to-xml` | criteria JSON → FTI019 | `authority/search.yml` |
| `search/request-to-json` | FTI019 → criteria JSON | `consignments/search-xml.yml` |
| `search/response-to-xml` | `ConsignmentRow[]` → FTI021 | `consignments/search-xml.yml` |
| `search/response-to-json` | one or more FTI021 (separated by `⦀`) → `ConsignmentRow[]` | `authority/search.yml` (broadcast task) |
| `dataset/request-to-xml` | `{uil, subsets}` → FTI009 | `authority/dataset.yml` (both), `dataset-local.yml` |
| `dataset/request-to-json` | FTI009 → `{uil, subsets}` | `dataset-xml.yml` |
| `dataset/response-to-xml` | `{uil, subsets, xml}` → FTI010 | `dataset-xml.yml` |
| `dataset/response-to-json` | FTI010 or bare consignment XML → `{consignment, xml}` | `authority/dataset.yml` (both) |
| `followup/request-to-xml` | `{uil, referenceIds, message, files}` → FTI025 | `authority/follow-up.yml`, `follow-up-local.yml` |
| `followup/request-to-json` | FTI025 → `{uil, referenceIds, message, files}` | `follow-up-xml.yml` |
| `followup/response-to-xml` | UIL JSON → FTI030 | `follow-up-xml.yml` |
| `followup/response-to-json` | FTI030 → UIL JSON | `authority/follow-up.yml` |
| `transport-means/normalize` | `get_consignments` rows or `ConsignmentRow`s → curated projection | `xroad/…/search.yml`, `xroad/…/transport-means.yml` |
| `GET /api/v1/openapi` | OpenAPI 3.1 of the routes above (needs the token) | — |
| `GET /health`, `GET /metrics` | see [Health](../operations/health.md#edelivery-and-xml-mapper) | probes |

The examples below use `code/xml-mapper/xsd/<FTI>/sample.xml`, run from `code/xml-mapper/xsd/`.
`T='X-Internal-Service-Token: dev-internal-service-token-change-me'`. Long XML is shortened with `…`;
the `ExchangedDocumentContext` block is the same in every generated document.

## upload/request-to-json

Maps a platform upload to the flat row ReSql `insert_consignment` stores.

- Body contains `FTI004UploadIdentifierRequest`: the UIL and the criteria come from the document.
- Otherwise the body is a bare `ParameterIDSetCriteria` and the UIL comes from `X-Gate-Id`,
  `X-Platform-Id`, `X-Dataset-Id` (UUID).

The `xml` field keeps the `ParameterIDSetCriteria` fragment as received.

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/upload/request-to-json -H "$T" \
  -H 'X-Request-Id: 3f1c2a8e-0000-4000-8000-00000000e001' -H 'Content-Type: text/xml' \
  --data-binary @FTI004/sample.xml
```

```http
HTTP/1.1 200 OK
Content-type: application/json; charset=UTF-8
X-request-id: 17022113-89b5-11f1-bec0-3c9c0f2eb459

{"acceptanceCountry":"DE","acceptanceDate":"2021-09-24T00:00:00Z","carriedEquipmentCategories":["BPR","BPR"],"carriedEquipmentIds":["TE-003","TE-004"],"carriedEquipmentSeq":[3,4],"dangerousGoods":"2","datasetId":"550e8400-e29b-41d4-a716-446655440000","deliveryCountry":"EE","deliveryDate":"2026-09-24T00:00:00Z","gateId":"EU-EE","loadingCountry":"FI","loadingDate":"2021-09-24T00:00:00Z","mainTransportId":"VESSEL-001","mainTransportType":"1513","platformId":"mock","transportMode":"1","transportRegCountry":"DE","unloadingCountry":"AE","unloadingDate":"2021-09-24T00:00:00Z","usedEquipmentCategories":["T10","T10"],"usedEquipmentCountries":["DE","DE"],"usedEquipmentIds":["TE-001","TE-002"],"usedEquipmentSeq":[1,2],"xml":"<ParameterIDSetCriteria xmlns:udt=\"urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34\">\n        <CarrierAcceptanceDateParameterScope>…</ParameterIDSetCriteria>"}
```

Bare criteria with the UIL headers:

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/upload/request-to-json -H "$T" -H 'Content-Type: text/xml' \
  -H 'X-Gate-Id: G1' -H 'X-Platform-Id: mock' -H 'X-Dataset-Id: 287baf20-06b0-4bf5-9697-0893c5317810' \
  --data-binary '<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>DOC-E-001</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>'
```

```http
HTTP/1.1 200 OK
Content-type: application/json; charset=UTF-8

{"carriedEquipmentCategories":[],"carriedEquipmentIds":[],"carriedEquipmentSeq":[],"datasetId":"287baf20-06b0-4bf5-9697-0893c5317810","gateId":"G1","mainTransportId":"DOC-E-001","platformId":"mock","usedEquipmentCategories":[],"usedEquipmentCountries":[],"usedEquipmentIds":[],"usedEquipmentSeq":[],"xml":"<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>DOC-E-001</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>"}
```

The same body without the three headers:

```http
HTTP/1.1 500 Internal Server Error
Content-type: application/json; charset=UTF-8

{"status":500,"title":"Internal Server Error"}
```

## upload/response-to-xml

Builds the FTI029 acknowledgement for an upload. Body: the UIL.

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/upload/response-to-xml -H "$T" \
  -H 'X-Request-Id: 3f1c2a8e-0000-4000-8000-00000000e001' -H 'Content-Type: application/json' \
  -d '{"gateId":"G1","platformId":"mock","datasetId":"287baf20-06b0-4bf5-9697-0893c5317810"}'
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 1155
<rsm:FTI029UploadIdentifierResponse xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI029UploadIdentifierResponse:1" xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34" xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34"><rsm:ExchangedDocumentContext>…</rsm:ExchangedDocumentContext><rsm:ExchangedDocument><ID schemeID="RFC 9562-4">46353d5c-ad4e-4b8d-bac8-c95de960b165</ID><TypeCode>029</TypeCode><StatusCode>C</StatusCode><IssueDateTime><udt:DateTimeString format="207">20261103160556Z</udt:DateTimeString></IssueDateTime><RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">3f1c2a8e-0000-4000-8000-00000000e001</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G1</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">287baf20-06b0-4bf5-9697-0893c5317810</DatasetID></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
  </rsm:FTI029UploadIdentifierResponse>
```

## search/request-to-xml

Criteria JSON (the shape `authority/search` receives) → FTI019.

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/search/request-to-xml -H "$T" \
  -H 'X-Request-Id: 3f1c2a8e-0000-4000-8000-00000000e002' -H 'Content-Type: application/json' \
  -d '{"mainTransportId":{"operator":"EQ","id":"G2-AB123"}}'
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 1134

<rsm:FTI019SearchIdentifierRequest …><rsm:ExchangedDocumentContext>…</rsm:ExchangedDocumentContext><rsm:ExchangedDocument><ID schemeID="RFC 9562-4">69c6afcd-6442-46ed-bfa1-b89ec2be3663</ID><TypeCode>019</TypeCode><IssueDateTime><udt:DateTimeString format="207">20261103160604Z</udt:DateTimeString></IssueDateTime><RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">3f1c2a8e-0000-4000-8000-00000000e002</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:EFTIIDInformation><ParameterSearchCriteria><MainCarriageTransportMeansIDParameterScope><SubtypeCode>EQ</SubtypeCode><ID>G2-AB123</ID></MainCarriageTransportMeansIDParameterScope></ParameterSearchCriteria></rsm:EFTIIDInformation>
  </rsm:FTI019SearchIdentifierRequest>
```

A `text/plain` body:

```http
HTTP/1.1 400 Bad Request
Content-type: application/json; charset=UTF-8

{"detail":"Cannot get criteria: Unsupported Content-Type: text/plain","instance":"/api/v1/search/request-to-xml","message":"Cannot get criteria: Unsupported Content-Type: text/plain","status":400,"title":"Bad Request"}
```

## search/request-to-json

FTI019 → criteria JSON. Date scopes become arrays (several `SubtypeCode`s are allowed).

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/search/request-to-json -H "$T" \
  -H 'Content-Type: text/xml' --data-binary @FTI019/sample.xml
```

```http
HTTP/1.1 200 OK
Content-type: application/json; charset=UTF-8
X-request-id: 17022113-89b5-11f1-bec0-3c9c0f2eb459

{"acceptanceCountry":{"country":"DE","operator":"EQ"},"acceptanceDate":[{"date":"2021-09-24T00:00:00Z","operator":"EQ"}],"carriedEquipmentId":{"id":"TE-004","operator":"EQ"},"deliveryCountry":{"country":"EE","operator":"EQ"},"deliveryDate":[{"date":"2025-06-01T00:00:00Z","operator":"GT"}],"loadingDate":[],"mainTransportId":{"id":"VESSEL-001","operator":"EQ"},"transportMode":{"mode":"1","operator":"EQ"},"unloadingDate":[]}
```

Not well-formed XML:

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/search/request-to-json -H "$T" -H 'Content-Type: text/xml' --data-binary '<notxml'
```

```http
HTTP/1.1 500 Internal Server Error
Content-type: application/json; charset=UTF-8

{"detail":"ParseError at [row,col]:[1,8]\nMessage: XML document structures must start and end within the same entity.","message":"ParseError at [row,col]:[1,8]\nMessage: XML document structures must start and end within the same entity.","status":500,"title":"Internal Server Error"}
```

## search/response-to-xml

`ConsignmentRow[]` → FTI021. Each row's `xml` (its `ParameterIDSetCriteria`) is put inside a
`UniqueIDSetUniqueIDSet`. An empty array gives an FTI021 with the note "No consignments found"
(`ContentCode` 404).

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/search/response-to-xml -H "$T" \
  -H 'X-Request-Id: 3f1c2a8e-0000-4000-8000-00000000e002' -H 'Content-Type: application/json' \
  -d '[{"gateId":"G1","platformId":"mock","datasetId":"287baf20-06b0-4bf5-9697-0893c5317810","mainTransportId":"DOC-E-001","xml":"<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>DOC-E-001</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>"}]'
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 1311
<rsm:FTI021SearchIdentifierResponse …>…<rsm:ExchangedDocument><ID schemeID="RFC 9562-4">678483f1-90a5-4082-a558-1d4cab217073</ID><TypeCode>021</TypeCode><StatusCode>C</StatusCode>…<RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">3f1c2a8e-0000-4000-8000-00000000e002</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G1</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">287baf20-06b0-4bf5-9697-0893c5317810</DatasetID><ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>DOC-E-001</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
  </rsm:FTI021SearchIdentifierResponse>
```

With `[]`:

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 1121

<rsm:FTI021SearchIdentifierResponse …>…<StatusCode>C</StatusCode>…<IncludedNote><IncludedNote><ContentCode>404</ContentCode><Content languageID="en">No consignments found</Content></IncludedNote></IncludedNote>…
    <rsm:EFTIIDInformation></rsm:EFTIIDInformation>
  </rsm:FTI021SearchIdentifierResponse>
```

## search/response-to-json

One or more FTI021 documents joined with `⦀` (U+2980) → one flat `ConsignmentRow[]`. The
broadcast task joins the peers' answers this way. Empty parts are skipped.

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/search/response-to-json -H "$T" \
  -H 'Content-Type: text/xml' --data-binary @FTI021/sample.xml
```

```http
HTTP/1.1 200 OK
Content-type: application/json; charset=UTF-8

[{"acceptanceDate":"2021-09-24T00:00:00Z","carriedEquipmentCategories":[],"carriedEquipmentIds":[],"carriedEquipmentSeq":[],"datasetId":"550e8400-e29b-41d4-a716-446655440000","gateId":"Gate-001","platformId":"Platform-001","transportMode":"1","usedEquipmentCategories":[],"usedEquipmentCountries":[],"usedEquipmentIds":[],"usedEquipmentSeq":[],"xml":"<ParameterIDSetCriteria xmlns:udt=\"urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34\">\n        <CarrierAcceptanceDateParameterScope>…</ParameterIDSetCriteria>"}]
```

The sample sent twice, joined with `⦀`, returned 2 rows.

## dataset/request-to-xml

`{uil, subsets}` → FTI009. Each subset becomes a `SubsetID`.

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/dataset/request-to-xml -H "$T" \
  -H 'X-Request-Id: 3f1c2a8e-0000-4000-8000-00000000e003' -H 'Content-Type: application/json' \
  -d '{"uil":{"gateId":"G2","platformId":"mock","datasetId":"d82c6c61-fb5a-4eed-8b30-59b05066918a"},"subsets":["EU01","EU02"]}'
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 1203
<rsm:FTI009GetCmdsRequest …>…<TypeCode>009</TypeCode>…<RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">3f1c2a8e-0000-4000-8000-00000000e003</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:MessageInformation><SubsetID>EU01</SubsetID><SubsetID>EU02</SubsetID></rsm:MessageInformation>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G2</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">d82c6c61-fb5a-4eed-8b30-59b05066918a</DatasetID></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
  </rsm:FTI009GetCmdsRequest>
```

Errors:

```bash
curl … -d '{"uil":{"gateId":"G1"}}'
curl … -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"not-a-uuid"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 400 Bad Request
Content-type: application/json; charset=UTF-8

{"detail":"Cannot create UIL from {gateId=G1}: missing platformId","instance":"/api/v1/dataset/request-to-xml","message":"Cannot create UIL from {gateId=G1}: missing platformId","status":400,"title":"Bad Request"}
```

```http
HTTP/1.1 400 Bad Request
Content-type: application/json; charset=UTF-8

{"detail":"Invalid UUID string: not-a-uuid","instance":"/api/v1/dataset/request-to-xml","message":"Invalid UUID string: not-a-uuid","status":400,"title":"Bad Request"}
```

## dataset/request-to-json

FTI009 → `{uil, subsets}`.

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/dataset/request-to-json -H "$T" \
  -H 'Content-Type: text/xml' --data-binary @FTI009/sample.xml
```

```http
HTTP/1.1 200 OK
Content-type: application/json; charset=UTF-8
X-request-id: 17022113-89b5-11f1-bec0-3c9c0f2eb459

{"subsets":["EE05b"],"uil":{"datasetId":"550e8400-e29b-41d4-a716-446655440000","gateId":"EU-EE","platformId":"mock"}}
```

A document without `ExchangedDocument`:

```bash
curl … --data-binary '<rsm:FTI009GetCmdsRequest xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI009GetCmdsRequest:1"/>'
```

```http
HTTP/1.1 400 Bad Request
Content-type: application/json; charset=UTF-8

{"detail":"Cannot create FTI009GetCmdsRequest from {}: missing document","instance":"/api/v1/dataset/request-to-json","message":"Cannot create FTI009GetCmdsRequest from {}: missing document","status":400,"title":"Bad Request"}
```

## dataset/response-to-xml

`{uil, subsets, xml}` → FTI010. `xml` is the platform's consignment XML. If it already contains an
`ExchangedDocument` (a full FTI010), it is passed through unchanged.

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/dataset/response-to-xml -H "$T" \
  -H 'X-Request-Id: 3f1c2a8e-0000-4000-8000-00000000e004' -H 'Content-Type: application/json' \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"287baf20-06b0-4bf5-9697-0893c5317810"},"subsets":["EU01"],"xml":"<SpecifiedSupplyChainConsignment><PackageQuantity>120</PackageQuantity></SpecifiedSupplyChainConsignment>"}'
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 1325
<rsm:FTI010GetCmdsResponse …>…<TypeCode>010</TypeCode><StatusCode>C</StatusCode>…</rsm:ExchangedDocument>
    <rsm:MessageInformation><SubsetID>EU01</SubsetID></rsm:MessageInformation>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G1</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">287baf20-06b0-4bf5-9697-0893c5317810</DatasetID></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
    <rsm:SpecifiedSupplyChainConsignment><PackageQuantity>120</PackageQuantity></rsm:SpecifiedSupplyChainConsignment>
  </rsm:FTI010GetCmdsResponse>
```

## dataset/response-to-json

FTI010, or a bare `SpecifiedSupplyChainConsignment`, → `{consignment, xml}`. `xml` is the
extracted consignment element; `consignment` is the same content as JSON (attributes as
`<name>@<attr>`). Field reference: [Dataset content (FTI010)](../../data/dataset-fields.md).

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/dataset/response-to-json -H "$T" \
  -H 'Content-Type: text/xml' --data-binary @FTI010/sample.xml
```

```http
HTTP/1.1 200 OK
Content-type: application/json; charset=UTF-8

{"consignment":{"grossWeightMeasure":"15000.00","grossWeightMeasure@unitCode":"KGM","netWeightMeasure":"12000.00","netWeightMeasure@unitCode":"KGM","grossVolumeMeasure":"45.00","grossVolumeMeasure@unitCode":"MTQ","packageQuantity":"120","transportEquipmentQuantity":"2","consignorTradeParty":{"Id":"DE123456789012345","Id@schemeAgencyId":"87","name":"Acme Logistics GmbH","postalTradeAddress":{"streetName":"Hauptstrasse","cityName":"Berlin","countryId":"DE"}},…},"xml":"<SpecifiedSupplyChainConsignment …>…"}
```

## followup/request-to-xml

`{uil, referenceIds, message, files}` → FTI025. `referenceIds` become `ReferencedID`s, `files`
become `AttachedSpecifiedBinaryFile`s.

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/followup/request-to-xml -H "$T" \
  -H 'X-Request-Id: 3f1c2a8e-0000-4000-8000-00000000e004' -H 'Content-Type: application/json' \
  -d '{"uil":{"gateId":"G2","platformId":"mock","datasetId":"d82c6c61-fb5a-4eed-8b30-59b05066918a"},"referenceIds":["5b0c8c55-1111-4000-8000-00000000e004"],"message":"Please show the CMR","files":[{"fileName":"note.txt","base64Content":"aGVsbG8=","mimeType":"text/plain"}]}'
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 1527
<rsm:FTI025LodgeFollowUpCommRequest …>…<TypeCode>025</TypeCode>…<ReferencedID schemeVersionID="RFC 9562-4">5b0c8c55-1111-4000-8000-00000000e004</ReferencedID><RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">3f1c2a8e-0000-4000-8000-00000000e004</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:MessageInformation><FollowUp languageID="en">Please show the CMR</FollowUp><AttachedSpecifiedBinaryFile><FileName>note.txt</FileName><MIMECode>text/plain</MIMECode><IncludedBinaryObject format="base64Binary">aGVsbG8=</IncludedBinaryObject></AttachedSpecifiedBinaryFile></rsm:MessageInformation>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G2</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">d82c6c61-fb5a-4eed-8b30-59b05066918a</DatasetID></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
  </rsm:FTI025LodgeFollowUpCommRequest>
```

## followup/request-to-json

FTI025 → `{uil, referenceIds, message, files}`.

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/followup/request-to-json -H "$T" \
  -H 'Content-Type: text/xml' --data-binary @FTI025/sample.xml
```

```http
HTTP/1.1 200 OK
Content-type: application/json; charset=UTF-8
X-request-id: 17022113-89b5-11f1-bec0-3c9c0f2eb459

{"files":[{"base64Content":"MTIzCg==","fileName":"Example","mimeType":"application/pdf"}],"message":"Follow-up: correction of consignee address","referenceIds":["158a5343-9fb4-11f1-ba2a-3c9c0f2eb459"],"uil":{"datasetId":"550e8400-e29b-41d4-a716-446655440000","gateId":"EU-EE","platformId":"mock"}}
```

## followup/response-to-xml

UIL → FTI030 (status `C`).

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/followup/response-to-xml -H "$T" \
  -H 'X-Request-Id: 3f1c2a8e-0000-4000-8000-00000000e004' -H 'Content-Type: application/json' \
  -d '{"gateId":"G1","platformId":"mock","datasetId":"287baf20-06b0-4bf5-9697-0893c5317810"}'
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 1158
<rsm:FTI030LodgeFollowUpCommResponse …>…<TypeCode>030</TypeCode><StatusCode>C</StatusCode>…</rsm:ExchangedDocument>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G1</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">287baf20-06b0-4bf5-9697-0893c5317810</DatasetID></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
  </rsm:FTI030LodgeFollowUpCommResponse>
```

## followup/response-to-json

FTI030 → UIL.

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/followup/response-to-json -H "$T" \
  -H 'Content-Type: text/xml' --data-binary @FTI030/sample.xml
```

```http
HTTP/1.1 200 OK
Content-type: application/json; charset=UTF-8
X-request-id: 17022113-89b5-11f1-bec0-3c9c0f2eb459

{"datasetId":"550e8400-e29b-41d4-a716-446655440000","gateId":"Gate-001","platformId":"Platform-001"}
```

## transport-means/normalize

Normalises core search results to the projection the X-Road `transport-means` and `search` routes
return (ADR-007 variant A): drops `xml`, `rowId` and the equipment sequence numbers, nests
`gateId/platformId/datasetId` as `uil`, and renders every key, with `null` where there is no
value. Local `get_consignments` rows and remote `ConsignmentRow`s get the same key set. Only this
route renders nulls.

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/transport-means/normalize -H "$T" \
  -H 'Content-Type: application/json' \
  -d '[{"datasetId":"22222222-0000-4000-8000-000000000001","gateId":"G2","mainTransportId":"G2-AB123","platformId":"mock","xml":"<x/>"}]'
```

```http
HTTP/1.1 200 OK
Content-type: application/json; charset=UTF-8
Content-length: 573
[{"acceptanceCountry":null,"acceptanceDate":null,"carriedEquipmentCategories":null,"carriedEquipmentIds":null,"createdAt":null,"dangerousGoods":null,"deliveryCountry":null,"deliveryDate":null,"loadingCountry":null,"loadingDate":null,"mainTransportId":"G2-AB123","mainTransportType":null,"status":null,"transportMode":null,"transportRegCountry":null,"uil":{"datasetId":"22222222-0000-4000-8000-000000000001","gateId":"G2","platformId":"mock"},"unloadingCountry":null,"unloadingDate":null,"usedEquipmentCategories":null,"usedEquipmentCountries":null,"usedEquipmentIds":null}]
```

A local row (from `authority/search` for TST-001) keeps its values:

```http
HTTP/1.1 200 OK
Content-type: application/json; charset=UTF-8
Content-length: 671
[{"acceptanceCountry":"EE","acceptanceDate":"2026-11-01T00:00:00Z","carriedEquipmentCategories":null,"carriedEquipmentIds":null,"createdAt":"2026-11-03T12:03:37.807711Z","dangerousGoods":"1","deliveryCountry":"LV","deliveryDate":"2026-11-02T00:00:00Z","loadingCountry":"EE","loadingDate":"2026-11-01T00:00:00Z","mainTransportId":"TST-001","mainTransportType":"31","status":"ACTIVE","transportMode":"3","transportRegCountry":"EE","uil":{"datasetId":"11111111-0000-4000-8000-000000000001","gateId":"G1","platformId":"mock"},"unloadingCountry":"LV","unloadingDate":"2026-11-02T00:00:00Z","usedEquipmentCategories":null,"usedEquipmentCountries":null,"usedEquipmentIds":null}]
```

## Without the token

```bash
curl -s -i -X POST http://127.0.0.1:27082/api/v1/search/request-to-xml -H 'Content-Type: application/json' -d '{}'
```

```http
HTTP/1.1 401 Unauthorized
Content-type: application/json; charset=UTF-8

{"detail":"Missing or invalid X-Internal-Service-Token","instance":"/api/v1/search/request-to-xml","message":"Missing or invalid X-Internal-Service-Token","status":401,"title":"Unauthorized"}
```

## Notes

- Malformed XML is a `500`, not a `400`. Callers cannot tell a bad message from a mapper fault by
  the status.
- `upload/request-to-json` throws a bare `500` when a bare criteria document comes without the
  `X-Gate-Id`/`X-Platform-Id`/`X-Dataset-Id` headers (Kotlin `!!`).
  `platforms/POST/v1/consignments.yml` sends all three (`x-dataset-id` from the path).
- The empty FTI021 has `<IncludedNote><IncludedNote>…</IncludedNote></IncludedNote>`, a nested
  element of the same name. Not checked against the FTI021 XSD here.
- In the FTI025 the `ReferencedID` carries `schemeVersionID="RFC 9562-4"`, every other id
  `schemeID`. Not checked against the XSD here.
- How the routes are chained per message: `docs/specs/data-transformations.md`.
