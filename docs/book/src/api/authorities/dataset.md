# dataset

Returns the content of one consignment dataset, addressed by its [UIL](../../data/uil.md) and
limited to the requested eFTI subsets. The gate first checks that every requested subset is in
the authority's `subsets`. If the UIL names this gate, the gate asks the platform. If it names
another gate, the gate sends FTI009 over AS4 to that gate, which asks its platform.

`POST /xroad/provide/v1/dataset` · on ruuter-xroad (G1 :27087, G2 :28087) · X-Road guard +
subset check · source `DSL/Ruuter-xroad/xroad/POST/provide/v1/dataset.yml`, forwarding to
`DSL/Ruuter/efti/POST/api/v1/authority/dataset.yml`

The X-Road clients below are development fixtures and public in the repo.
`DEV/GOV/70000093/ants` holds `EU01`, `EU02`.

## Request

| Header | Required | Value |
|---|---|---|
| `X-Road-Client` | yes | the caller |
| `X-Road-Id` | yes | a new UUID. A repeated id is rejected with `409`. Keep it: it is the `referenceIds` value for a [follow-up](./follow-up.md). |
| `Content-Type` | yes | `application/json` |

| Body field | Required | Type | Meaning / validation |
|---|---|---|---|
| `uil` | yes | object | `{gateId, platformId, datasetId}`, usually taken from a `search` or `transport-means` row |
| `uil.gateId` | yes | string | `G1`, `G2`, … If it is not this gate's id, the request goes to that gate. |
| `uil.platformId` | yes | string | platform id in the registry of the gate that holds the dataset |
| `uil.datasetId` | yes | string | the platform's dataset id (a UUID) |
| `subsets` | yes | array of strings, at least one | `EU01`…`EU07`; each must be in the authority's `subsets` |

The route checks only that `uil` is present. The parts of `uil` are not validated (see Notes).

## Responses

Checks run in this order.

| Status | When | Body |
|---|---|---|
| 400 (engine) | `uil` not an object, `subsets` not an array | `{"error":"Field type mismatch in body: uil expected object, got string"}` and the same for `subsets` |
| 400 `BAD_REQUEST_GENERAL` | no body or no `uil` | "The request body is missing or malformed. …" |
| 400 `MISSING_SUBSET` | `subsets` missing or `[]` | "At least one subset must be requested." |
| 400 `BAD_REQUEST_GENERAL` | an element of `subsets` is not a string | same detail as above |
| 403 `FORBIDDEN_SUBSET` | at least one requested subset is not permitted | `authorityId`, `deniedSubsets` (unique), `permittedSubsets` |
| 403 `FORBIDDEN` | the permission could not be evaluated | "The subset permission of the X-Road client could not be established." Not reachable with the registry fixtures. |
| 409 `DUPLICATE_REQUEST_ID` | this `X-Road-Id` was used for a `dataset` or `follow-up` before | `application/problem+json` |
| 200 | the dataset was fetched | `uil` (as sent), `subsets` (as sent), `xml` (the FTI010 `SpecifiedSupplyChainConsignment` fragment), `consignment` (the same content as JSON) |
| 502 `GATEWAY_UNAVAILABLE` | core answered ≥ 400 | `coreStatus`, `coreResponse` (core's own body) |
| 502 `GATEWAY_UNAVAILABLE` | core unreachable or no answer in 70 s | `transportError`. Not run locally. |

Core's answers behind a `502`:

| `coreStatus` | `coreResponse` | Cause |
|---|---|---|
| 400 | `{"error":"Remote gate dataset request failed","detail":{…"Unknown party: EU-XX"…}}` | `uil.gateId` is no gate in the registry |
| 400 | `{"type":…,"code":"BAD_REQUEST_GENERAL",…}` | a subset that is permitted but not `EU01`…`EU07` (core validates the codes). Not run: no fixture authority holds such a code. |
| 404 | `{"error":"Platform lookup failed (data layer unavailable)"}` | `uil.platformId` unknown at this gate (see Notes) |
| 404 | `{"error":"Platform Not Found"}` | platform known but not `ONLINE`. Not run locally. |
| 504 | `{"error":"Remote gate dataset request failed","detail":{…"No data received after 1m"…}}` | `uil.platformId` unknown at the other gate: it never answers |
| 502 | `{"error":"The remote gate is unreachable",…}` / `{"error":"XML mapper unavailable"}` | Not run locally. |

## Examples

### Local dataset at G1

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "consignment": {
    "grossWeightMeasure": "15000.00",
    "grossWeightMeasure@unitCode": "KGM",
    "mainCarriageLogisticsTransportMovement": {
      "modeCode": "3",
      "usedLogisticsTransportMeans": {
        "Id": "MOCK-PLATE-1",
        "Id@schemeAgencyId": "5",
        "registrationTradeCountry": {"Id": "EE"}
      }
    },
    "packageQuantity": "120"
  },
  "subsets": ["EU01"],
  "uil": {
    "datasetId": "11111111-0000-4000-8000-000000000001",
    "gateId": "G1",
    "platformId": "mock"
  },
  "xml": "<SpecifiedSupplyChainConsignment\n    xmlns:rsm=\"urn:eu:move:eFTI:data:…"
}
```

The full `xml` value:

```xml
<SpecifiedSupplyChainConsignment
    xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI010GetCmdsResponse:1"
    xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34"
    xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
  <GrossWeightMeasure unitCode="KGM">15000.00</GrossWeightMeasure>
  <PackageQuantity>120</PackageQuantity>
  <MainCarriageLogisticsTransportMovement>
    <ModeCode>3</ModeCode>
    <UsedLogisticsTransportMeans>
      <ID schemeAgencyID="5">MOCK-PLATE-1</ID>
      <RegistrationTradeCountry><ID>EE</ID></RegistrationTradeCountry>
    </UsedLogisticsTransportMeans>
  </MainCarriageLogisticsTransportMovement>
</SpecifiedSupplyChainConsignment>
```

The content comes from the `mock` platform (`DSL/Ruuter/mock-platform/GET/v1/dataset.yml`), which
serves the same fixture for every dataset id. That is why the plate is `MOCK-PLATE-1` and not
`TST-001`. [Dataset content (FTI010)](../../data/dataset-fields.md) explains `consignment`.

### Remote dataset: G1 asks G2

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G2","platformId":"mock","datasetId":"22222222-0000-4000-8000-000000000001"},"subsets":["EU01","EU02"]}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "consignment": {
    "grossWeightMeasure": "15000.00",
    "grossWeightMeasure@unitCode": "KGM",
    "mainCarriageLogisticsTransportMovement": { … "Id": "MOCK-PLATE-1" … },
    "packageQuantity": "120"
  },
  "subsets": ["EU01", "EU02"],
  "uil": {
    "datasetId": "22222222-0000-4000-8000-000000000001",
    "gateId": "G2",
    "platformId": "mock"
  },
  "xml": "<SpecifiedSupplyChainConsignment xmlns:udt=\"urn:eu:move:eFTI:data:stan…"
}
```

The round trip (FTI009 → G2 → mock platform → FTI010 → G1) took 0.22 s. The remote
`xml` is cut out of the FTI010 message:

```xml
<SpecifiedSupplyChainConsignment xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34"><GrossWeightMeasure unitCode="KGM">15000.00</GrossWeightMeasure>
  <PackageQuantity>120</PackageQuantity>
  <MainCarriageLogisticsTransportMovement>
    …
  </MainCarriageLogisticsTransportMovement></SpecifiedSupplyChainConsignment>
```

### Remote dataset: G2 asks G1

```bash
curl -s -X POST http://127.0.0.1:28087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "consignment": { …same as above… },
  "subsets": ["EU01"],
  "uil": {"datasetId": "11111111-0000-4000-8000-000000000001", "gateId": "G1", "platformId": "mock"},
  "xml": "<SpecifiedSupplyChainConsignment xmlns:udt=\"urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34\"><GrossWeightMeasure …"
}
```

### Subset not permitted → 403

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01","EU06"]}'
```

```http
HTTP/1.1 403 Forbidden
content-type: application/json

{
  "authorityId": "auth-xroad-tm",
  "code": "FORBIDDEN_SUBSET",
  "deniedSubsets": ["EU06"],
  "detail": "The requesting authority is not permitted to access one or more of the requested eFTI subsets.",
  "permittedSubsets": ["EU01", "EU02"],
  "status": 403,
  "title": "Subset Access Denied",
  "type": "https://api.efti.ee/errors/forbidden-subset"
}
```

The request is denied as a whole; the gate does not narrow it to `EU01`. `"subsets":["EU09"]`
(not a real subset) gives `"deniedSubsets": ["EU09"]`. An authority with no subsets
(`DEV/GOV/70000096/x`) gets `"deniedSubsets": ["EU01"], "permittedSubsets": []`.

### Missing subsets → 400

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":[]}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json

{
  "code": "MISSING_SUBSET",
  "detail": "At least one subset must be requested.",
  "status": 400,
  "title": "Missing Subset Parameter",
  "type": "https://api.efti.ee/errors/missing-subset"
}
```

Without the `subsets` key: the same answer.

### Missing uil, wrong types → 400

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" -d '{}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json

{
  "code": "BAD_REQUEST_GENERAL",
  "detail": "The request body is missing or malformed. It must be a JSON object with 'uil' (object) and 'subsets' (non-empty array of subset codes).",
  "status": 400,
  "title": "Bad Request",
  "type": "https://api.efti.ee/errors/bad-request"
}
```

`{"subsets":["EU01"]}` (no `uil`) and `"subsets":[1]` give the same body. `"subsets":"EU01"` is
rejected by the engine:

```json
{"error": "Field type mismatch in body: subsets expected array, got string"}
```

### The same X-Road-Id twice → 409

```bash
ID=$(cat /proc/sys/kernel/random/uuid)
BODY='{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
for i in 1 2; do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
    -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
    -H "X-Road-Id: $ID" -d "$BODY"
done
```

```text
200
409
```

The `409` body (`curl -i` on the second call):

```http
HTTP/1.1 409 Conflict
content-type: application/problem+json

{
  "code": "DUPLICATE_REQUEST_ID",
  "detail": "This X-Road-Id has already been processed. Retry with a new request id if this is a new request.",
  "status": 409,
  "title": "Duplicate Request ID",
  "type": "https://api.efti.ee/errors/duplicate-request-id"
}
```

### Unknown gate → 502

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"EU-XX","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json

{
  "code": "GATEWAY_UNAVAILABLE",
  "coreResponse": {
    "detail": {
      "detail": "Unknown party: EU-XX",
      "instance": "/api/v1/send/EU-XX",
      "message": "Unknown party: EU-XX",
      "status": 400,
      "title": "Bad Request"
    },
    "error": "Remote gate dataset request failed"
  },
  "coreStatus": 400,
  "detail": "The gate's dataset request failed. The gate's own status and response are carried in coreStatus and coreResponse.",
  "status": 502,
  "title": "Bad Gateway",
  "type": "https://api.efti.ee/errors/bad-gateway"
}
```

### Unknown platform at this gate → 502

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"nope","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json

{
  "code": "GATEWAY_UNAVAILABLE",
  "coreResponse": {"error": "Platform lookup failed (data layer unavailable)"},
  "coreStatus": 404,
  "detail": "The gate's dataset request failed. The gate's own status and response are carried in coreStatus and coreResponse.",
  "status": 502,
  "title": "Bad Gateway",
  "type": "https://api.efti.ee/errors/bad-gateway"
}
```

### Unknown platform at the other gate → 502 after one minute

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G2","platformId":"nope","datasetId":"22222222-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json

{
  "code": "GATEWAY_UNAVAILABLE",
  "coreResponse": {
    "detail": {
      "detail": "No data received after 1m",
      "instance": "/api/v1/send/G2",
      "message": "No data received after 1m",
      "status": 504,
      "title": "Gateway Timeout"
    },
    "error": "Remote gate dataset request failed"
  },
  "coreStatus": 504,
  "detail": "The gate's dataset request failed. The gate's own status and response are carried in coreStatus and coreResponse.",
  "status": 502,
  "title": "Bad Gateway",
  "type": "https://api.efti.ee/errors/bad-gateway"
}
```

### Unknown dataset id → 200

```bash
curl -s -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"99999999-0000-4000-8000-000000000009"},"subsets":["EU01"]}'
```

```json
{
  "consignment": { …"Id": "MOCK-PLATE-1"… },
  "subsets": ["EU01"],
  "uil": {"datasetId": "99999999-0000-4000-8000-000000000009", "gateId": "G1", "platformId": "mock"},
  "xml": "<SpecifiedSupplyChainConsignment\n    xmlns:rsm=\"urn:eu:move:eFTI:data:…"
}
```

The gate does not check that it holds an identifier row for the dataset; it asks the platform,
and the mock platform answers every id. The same happens for `gateId: "G2"` and for a
`datasetId` that is not a UUID (`"not-a-uuid"`) or missing.

## Notes

- **Unknown platform is reported as an outage.** The registry answers `404` for an unknown
  platform id, and `DSL/Ruuter/efti/POST/api/v1/dataset-local.yml` treats every status ≥ 300 as
  "Platform lookup failed (data layer unavailable)". Core then returns it with status `404`. The
  `PLATFORM_NOT_FOUND` branch is only reached for a platform that exists but is not `ONLINE`.
- **An unknown platform at the other gate costs one minute.** G2 does not answer the FTI009, so
  G1's edelivery waits 60 s and returns `504`.
- `uil.platformId` and `uil.datasetId` are not validated. A real platform decides what to do with
  an unknown id; the mock platform returns its fixture.
- The X-Road route accepts any string as a subset as long as the authority holds it. Core's
  `authority/dataset.yml` accepts only `EU01`…`EU07` and answers `400` otherwise, which the
  X-Road route would return as `502` with `coreStatus: 400`. Not run: no fixture authority holds
  a code outside `EU01`…`EU07`.
- The dedup table (`request_id_cache`) is shared by `dataset` and `follow-up`: a `follow-up`
  sent with the `X-Road-Id` of an earlier `dataset` call answered `409 DUPLICATE_REQUEST_ID`.
- Repeated subsets are passed through: `["EU01","EU01"]` answers `200` with
  `"subsets": ["EU01","EU01"]`.
- The integration guide says the response is "the dataset XML unchanged" with keys
  `{uil, subsets, xml}`; the route also returns `consignment`, and the remote `xml` is a cut-out
  without the default namespace declaration.
- Postman: **1. CA -> Gate / Dataset and follow-up / dataset - local**, **… - remote (peer gate, AS4
  FTI009)**, **… - subset not held -> 403**, **… - missing uil/subsets -> 400**.
- Use case: [An authority retrieves a dataset](../../use-cases/authority-retrieves-dataset.md).
