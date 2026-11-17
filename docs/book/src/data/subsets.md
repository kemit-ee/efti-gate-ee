# eFTI subsets

An eFTI subset is a named part of the eFTI common data set: the data one legal act lets an
authority see. An authority asks for a dataset "in subsets EU01 and EU02". The gate checks that
the authority may see those subsets and passes the list on to the platform, which cuts the
dataset down.

Subsets are defined in Commission Delegated Regulation (EU) 2024/2024, which the repository cites
(`docs/specs/non-functional.md` §5, `DSL/Ruuter-xroad/xroad/POST/provide/v1/transport-means.yml`).
Codes for Union law start with `EU`. Codes for national law start with the country code: `EE01`,
`EE05b`, `LV03`, …

The X-Road clients on this page are development fixtures, public in the repo
([Ports and credentials](../reference/ports-and-credentials.md)).

## The codes

The gate treats a subset as an opaque string. Its API accepts `EU01`…`EU07`. What each code
contains is described differently in four places in the repository:

| Code | `docs/specs/api_endpoints.md`, `docs/developer/x_road_openapi.yaml` | `docs/specs/data-transformations.md` | `docs/efti-analysis/1-analysis/analysis_doc_eng.md` | XSD code list CL-056 |
|---|---|---|---|---|
| `EU01` | consignment identification | identification, parties, transport contract | full dataset, all regulatory requirements | yes |
| `EU02` | means of transport | goods, packaging, weight, volume | combined transport | yes |
| `EU03` | transported goods | itinerary, places, dates | waste transport | yes |
| `EU04` | locations | vehicle / vessel / aircraft identifiers | food safety, animal and plant health | no |
| `EU05` | dangerous goods | dangerous goods detail | dangerous goods (ADR/RID/ADN) | as `EU05a`, `EU05b`, `EU05c` |
| `EU06` | waste shipment | operator / carrier identifiers | cabotage | yes |
| `EU07` | consignee / consignor commercial data | customs and cross-border clearance | – | no |

The code that uses subsets follows the first column for `EU02`:
`transport-means` requires `EU02` because it returns means-of-transport data. The admin
consignment summary maps `EU02` to dangerous goods, `EU03` to loading and unloading country and
`EU04` to transport mode and registration country ([Consignments](../api/admin/consignments.md)).
ADR-006 lists the disagreement as open. Check the regulation before you rely on a meaning.

The XSD code list (`SubsetID`, `udt:IDType_5`, `code/xml-mapper/xsd/FTI009/…UnqualifiedDataType_34.xsd`)
has `EU01`, `EU02`, `EU03`, `EU05a`, `EU05b`, `EU05c`, `EU06` and the national codes of every
member state, for example `EE01`, `EE02`, `EE04`, `EE05a`–`EE05d`, `LV01`–`LV05`. The sample
`code/xml-mapper/xsd/FTI009/sample.xml` asks for `EE05b`.

## What the gate does with subsets

1. It checks the requested subsets against the authority's entitlement (X-Road routes only).
2. It passes the list to the platform: `GET {baseUrl}/v1/dataset/{datasetId}?subsets=EU01,EU02`
   for a REST platform, `SubsetID` elements in the FTI009 for a peer gate or an eDelivery
   platform.
3. It does not filter the dataset. The platform must return only what the subsets allow.

The `mock` platform ignores `subsets` (`DSL/Ruuter/mock-platform/GET/v1/dataset.yml`). Asking for
`["EU01"]` and for `["EU01","EU02"]` for the same UIL returns the same `xml` and `consignment`; the
two answers differ only in the echoed `subsets` array (61 789 and 61 796 bytes for
`EU-EE/mock/550e8400-e29b-41d4-a716-446655440002`).

## Entitlement: `registry/authorities/*.yml`

Each competent authority is one YAML file. `subsets` lists what it may request.

```yaml
id: auth-xroad-tm
name: X-Road Transport Means Authority
registryCode: '70000093'
subsets:
- EU01
- EU02
```

| Rule | Enforced by |
|---|---|
| `subsets` is required and is a list | `scripts/registry-to-json.py` (`registry-build` fails) |
| every entry matches `^EU0[1-7]$`; no duplicates | same |
| `subsets: []` means "entitled to nothing" and stays in the JSON | same |
| the X-Road memberCode is matched against `registryCode` (`===`, string) | `DSL/Ruuter-xroad/xroad/.guard.yml` |
| two files with the same `registryCode` deny both (`403 FORBIDDEN`) | same |

A change means editing the file and re-running `registry-build`
(`docker compose up -d registry-build`); there is no API to change entitlements. Because the
converter accepts only `EU01`…`EU07`, no authority can be entitled to a national code.

Fixtures in this repo:

| X-Road client (memberCode) | Authority | `subsets` |
|---|---|---|
| `DEV/GOV/70000099/…` | `auth-xroad-test` | `EU01` |
| `DEV/GOV/70000093/…` | `auth-xroad-tm` | `EU01`, `EU02` |
| `DEV/GOV/70000094/…` | `auth-xroad-fwd` | `EU01`, `EU02` |
| `DEV/GOV/70000097/…` | `auth-xroad-subsets` | `EU01`, `EU05` |
| `DEV/GOV/70000092/…` | `auth-xroad-tm-no-eu02` | `EU01` |
| `DEV/GOV/70000096/…` | `auth-xroad-empty` | none |
| `DEV/GOV/70000771/…` | `auth-summary-eu03` | `EU03` |
| `DEV/GOV/70000772/…` | `auth-summary-none` | none |
| `DEV/GOV/70000091/…` | `auth-xroad-ambig-a` and `auth-xroad-ambig-b` | ambiguous, always `403` |

## Where the gate checks subsets

| Route | Check | Failure |
|---|---|---|
| X-Road `POST /xroad/provide/v1/dataset` | `subsets` non-empty array of strings; every element in the authority's `subsets` (exact, case-sensitive) | `400 MISSING_SUBSET`, `400 BAD_REQUEST_GENERAL`, `403 FORBIDDEN_SUBSET` |
| X-Road `POST /xroad/provide/v1/transport-means` | the authority has `EU02`, for every `scope` | `403 FORBIDDEN_SUBSET` with `deniedSubsets: ["EU02"]` |
| X-Road `search`, `follow-up`, `echo` | none: they return identifiers, not content | – |
| X-Road `GET /xroad/provide/v1/subsets` | none; returns the entitlement | – |
| core `POST /efti/api/v1/authority/dataset` | every element is one of `EU01`…`EU07` (no entitlement check; core trusts its caller) | `400 BAD_REQUEST_GENERAL` |
| core `GET /efti/api/v1/authority/dataset/{gate}/{platform}/{id}?subsets=` | same | `400 BAD_REQUEST_GENERAL` |
| inbound FTI009 from a peer gate | none at the gate; the list goes to the platform as received | – |
| xml-mapper (any FTI message) | each code is `full`, `identifier` or starts with a country code from its list (`EU`, `EE`, `LV`, …) | `400 "Subset 'XX01' should start with country code"` |
| developer mock `POST /developer/v1/dataset` | memberCode `70000000` is denied everything | `403 FORBIDDEN_SUBSET`, `permittedSubsets: []` |
| admin `GET /admin/v1/consignment-summary` | dimensions are shown only for the authority's subsets | see [Consignments](../api/admin/consignments.md) |

`FORBIDDEN_SUBSET` lists each denied code once (`deniedSubsets` is de-duplicated) and the full
entitlement in `permittedSubsets`. The whole request is refused; the gate never narrows a request
to the permitted part.

## Examples

### Ask for the entitlement

```bash
curl -i http://127.0.0.1:27087/xroad/provide/v1/subsets \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```http
HTTP/1.1 200 OK
content-type: application/json
traceparent: 00-a990ad9a2a9b447ace876fd5a757a749-9bccae2d5ec9607d-01
x-trace-id: a990ad9a2a9b447ace876fd5a757a749

{"authorityId":"auth-xroad-tm","registryCode":"70000093","subsets":["EU01","EU02"]}
```

Other clients, same request:

| `X-Road-Client` | Answer |
|---|---|
| `DEV/GOV/70000099/testSubsystem` | `200 {"authorityId":"auth-xroad-test","registryCode":"70000099","subsets":["EU01"]}` |
| `DEV/GOV/70000097` (member level, no subsystem) | `200 {"authorityId":"auth-xroad-subsets","registryCode":"70000097","subsets":["EU01","EU05"]}` |
| `DEV/GOV/70000096/x` | `200 {"authorityId":"auth-xroad-empty","registryCode":"70000096","subsets":[]}` |
| `DEV/GOV/12345678/x` | `403 FORBIDDEN` "The X-Road client does not resolve to an active competent authority." |
| `DEV/GOV/70000091/x` | `403 FORBIDDEN` "…resolves to more than one active competent authority…" |

More in [subsets](../api/authorities/subsets.md).

### Dataset with a subset the authority does not have → 403

`EU06` is asked for twice; it appears once in `deniedSubsets`.

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01","EU06","EU06"]}'
```

```http
HTTP/1.1 403 Forbidden
content-type: application/json
traceparent: 00-00a94843f3f0fef4b0c5c11ffd735c6d-f51255a0f32fe487-01
x-trace-id: 00a94843f3f0fef4b0c5c11ffd735c6d

{"authorityId":"auth-xroad-tm","code":"FORBIDDEN_SUBSET","deniedSubsets":["EU06"],"detail":"The requesting authority is not permitted to access one or more of the requested eFTI subsets.","permittedSubsets":["EU01","EU02"],"status":403,"title":"Subset Access Denied","type":"https://api.efti.ee/errors/forbidden-subset"}
```

### A national code → 403

No authority can hold `EE05b`, so the X-Road route always denies it.

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EE05b"]}'
```

```http
HTTP/1.1 403 Forbidden
content-type: application/json
traceparent: 00-a687acce438fe6daa5e89418c016ed15-841af84afa648a7d-01
x-trace-id: a687acce438fe6daa5e89418c016ed15

{"authorityId":"auth-xroad-tm","code":"FORBIDDEN_SUBSET","deniedSubsets":["EE05b"],"detail":"The requesting authority is not permitted to access one or more of the requested eFTI subsets.","permittedSubsets":["EU01","EU02"],"status":403,"title":"Subset Access Denied","type":"https://api.efti.ee/errors/forbidden-subset"}
```

### Authority with no subsets → 403

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000096/x' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 403 Forbidden
content-type: application/json
traceparent: 00-5ed199ba4986335c2423439734709fcb-4f1ae74eb43ede9f-01
x-trace-id: 5ed199ba4986335c2423439734709fcb

{"authorityId":"auth-xroad-empty","code":"FORBIDDEN_SUBSET","deniedSubsets":["EU01"],"detail":"The requesting authority is not permitted to access one or more of the requested eFTI subsets.","permittedSubsets":[],"status":403,"title":"Subset Access Denied","type":"https://api.efti.ee/errors/forbidden-subset"}
```

### No subsets requested → 400

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":[]}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
traceparent: 00-6e8b574d0ba93d96ce36326f3bf1e895-a17c8e3974101914-01
x-trace-id: 6e8b574d0ba93d96ce36326f3bf1e895

{"code":"MISSING_SUBSET","detail":"At least one subset must be requested.","status":400,"title":"Missing Subset Parameter","type":"https://api.efti.ee/errors/missing-subset"}
```

`"subsets":"EU01"` (a string) is stopped by the engine:
`400 {"error":"Field type mismatch in body: subsets expected array, got string"}`.

### Transport means without EU02 → 403

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" -d '{"identifier":"TST-001"}'
```

```http
HTTP/1.1 403 Forbidden
content-type: application/json
traceparent: 00-545f0a47c6ed15db15d0dc142ab3f948-867594a9ad00cf51-01
x-trace-id: 545f0a47c6ed15db15d0dc142ab3f948

{"authorityId":"auth-xroad-test","code":"FORBIDDEN_SUBSET","deniedSubsets":["EU02"],"detail":"A transport-means lookup returns means-of-transport data (EU02), which this authority is not permitted to access.","permittedSubsets":["EU01"],"status":403,"title":"Subset Access Denied","type":"https://api.efti.ee/errors/forbidden-subset"}
```

`DEV/GOV/70000096/x` gets the same with `"authorityId":"auth-xroad-empty"` and
`"permittedSubsets":[]`.

### Core: a code outside EU01…EU07 → 400

```bash
curl -i -X POST http://127.0.0.1:27086/efti/api/v1/authority/dataset \
  -H 'Content-Type: application/json' \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EE05b"]}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
traceparent: 00-3fae35eb12621fbaf9569bb58a24ccc6-c174e86bfbc375cf-01
x-trace-id: 3fae35eb12621fbaf9569bb58a24ccc6

{"code":"BAD_REQUEST_GENERAL","detail":"The request body must be a JSON object with 'uil' (object) and a non-empty 'subsets' array of EU01..EU07 codes.","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

The path form `GET /efti/api/v1/authority/dataset/G1/mock/11111111-0000-4000-8000-000000000001?subsets=EU99`
answers `400` with "The query parameter 'subsets' must list at least one subset, each one of
EU01..EU07.". `["EU01","EU07"]` is accepted (`200`) by core, because core checks only the code
list.

## Subsets in FTI009 and FTI010

In the gate-to-gate messages the subsets are `SubsetID` elements in `rsm:MessageInformation`, one
per code, in the order requested. FTI010 repeats the list of the FTI009 it answers.

FTI009, built by xml-mapper (`POST /api/v1/dataset/request-to-xml`, body
`{"uil":{…G2…},"subsets":["EU01","EE05b"]}`), excerpt:

```xml
    <rsm:MessageInformation><SubsetID>EU01</SubsetID><SubsetID>EE05b</SubsetID></rsm:MessageInformation>
```

FTI010 from G2 for an FTI009 with `EU01`, `EU02` (sent with
`POST http://127.0.0.1:27081/api/v1/send/G2`, consignment cut):

```xml
<rsm:FTI010GetCmdsResponse xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI010GetCmdsResponse:1" xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34" xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34"><rsm:ExchangedDocumentContext>…</rsm:ExchangedDocumentContext><rsm:ExchangedDocument><ID schemeID="RFC 9562-4">e0c9c1bd-794f-4d31-9095-37b854f91c2e</ID><TypeCode>010</TypeCode><StatusCode>C</StatusCode><IssueDateTime><udt:DateTimeString format="207">20261103162954Z</udt:DateTimeString></IssueDateTime><RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">c301c37f-e1ce-4fd6-bdad-4ec29d598631</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:MessageInformation><SubsetID>EU01</SubsetID><SubsetID>EU02</SubsetID></rsm:MessageInformation>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G2</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">22222222-0000-4000-8000-000000000001</DatasetID></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
    <rsm:SpecifiedSupplyChainConsignment>…</rsm:SpecifiedSupplyChainConsignment>
  </rsm:FTI010GetCmdsResponse>
```

The receiving gate does not check `SubsetID` against anything. An FTI009 with
`<SubsetID>EE05b</SubsetID>` sent from G1 to G2 answered `200`, and G2's log shows the
code passed to the platform as is:

```text
▸ query_rest_platform (http) 3.9ms → check_dataset_xml  method="GET" url="http://ruuter:8086/mock-platform/v1/dataset/22222222-0000-4000-8000-000000000001?subsets=EE05b" status=200
```

xml-mapper refuses a code without a known country prefix:

```bash
curl -i -X POST http://127.0.0.1:27082/api/v1/dataset/request-to-xml \
  -H 'Content-Type: application/json' \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G2","platformId":"mock","datasetId":"22222222-0000-4000-8000-000000000001"},"subsets":["XX01"]}'
```

```http
HTTP/1.1 400 Bad Request
Content-type: application/json; charset=UTF-8

{"detail":"Subset 'XX01' should start with country code","instance":"/api/v1/dataset/request-to-xml","message":"Subset 'XX01' should start with country code","status":400,"title":"Bad Request"}
```

## Notes

- The subset vocabulary differs per layer: the registry and core accept `EU01`…`EU07`; the XSD
  has `EU05a/b/c` and no `EU04`/`EU07`; xml-mapper accepts any country-prefixed code plus `full`
  and `identifier`. An FTI009 from a peer that asks for `EU05a` reaches the platform; an Estonian
  authority cannot ask for `EU05a` at all.
- `INVALID_SUBSET` exists in `errors.json` but no route emits it; core answers
  `BAD_REQUEST_GENERAL` ([Error catalogue](./errors.md)).
- The subset check compares strings exactly. `eu01` is denied with `403 FORBIDDEN_SUBSET`, not
  rejected as malformed.
- Postman: folder **1. CA -> Gate / Transport means (EU02)** has the `client without EU02 -> 403` request
  ([The Postman collection](../reference/collection.md)).
- Related: [dataset](../api/authorities/dataset.md), [transport-means](../api/authorities/transport-means.md),
  [Permissions matrix](./permissions.md).
