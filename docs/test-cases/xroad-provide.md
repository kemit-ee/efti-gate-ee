# X-Road provider adapter — `/xroad/provide/**`

Base URL per environment (reachable only from an allowed address, see [README](README.md)):

```bash
BASE=https://eftiprelive.xtpnl.kemitaws.ee/xroad     # or eftidev / eftitest
```

Test clients (dev fixtures in `registry/authorities/`; a deployed registry is built from its own
files, so confirm the codes exist there first):

| `X-Road-Client` | Authority | `subsets` |
|---|---|---|
| `EE/GOV/70000099/testSubsystem` | `auth-xroad-test` | `EU01` |
| `EE/GOV/70000093/ants` | `auth-xroad-tm` | `EU01`, `EU02` |

`X-Road-Id` must be a UUID in 8-4-4-4-12 hex form on every call under `/xroad/provide/v1/`. The
cases use `0a11ce5e-NNNN-4d1d-8f1a-0ec1NNNNNNNN`.

Error bodies are RFC 7807-style: `type`, `code`, `title`, `status`, `detail`.

Sources: **observed (prelive)** = seen on the prelive X-Road host on 2026-11-03; **observed (local)** =
run on 2026-11-03 against the local compose stack (`docker compose up --build -d`, then the
[test data](data/README.md) loaded) with `BASE=http://localhost:8087/xroad`. The local stack also holds
`VESSEL-001` and the other fixtures that `tests/` upload, so counts such as "all consignments"
differ from a clean environment; the cases below avoid depending on them.

---

## 1. Service description

### 1.1 openapi: public, no client header → 200

```bash
curl -i $BASE/provide/openapi
```

- Status **200**, `content-type: application/json`.
- Body: the OpenAPI document (`openapi`, `info`, `servers: /xroad/provide`, `paths`, `components`).
- Source: observed (prelive, 2026-11-03) for status and shape; contents generated from
  `docs/developer/x_road_openapi.yaml`.

### 1.2 health: not exposed through the route → 404

```bash
curl -i $BASE/health/ready
```

- Status **404**, body `404 page not found` (plain text, from the gateway; the HTTPRoute forwards only
  the prefix `/xroad/provide`).
- Locally (no gateway in front) the route answers **200** with body `"OK"`.
- Source: observed (prelive, 2026-11-03; local 2026-11-03).

---

## 2. Client identity (guard)

### 2.1 echo: known client → 200

```bash
curl -i -X POST $BASE/provide/v1/echo \
  -H "Content-Type: application/json" \
  -H "X-Road-Client: EE/GOV/70000099/testSubsystem" \
  -H "X-Road-Id: 0a11ce5e-0001-4d1d-8f1a-0ec100000001" \
  -H "X-Road-UserId: EE30101010007" \
  -d '{"hello":"world"}'
```

- Status **200**.
- Body:
  ```json
  {
    "xRoadClient": "EE/GOV/70000099/testSubsystem",
    "xRoadId": "0a11ce5e-0001-4d1d-8f1a-0ec100000001",
    "xRoadUserId": "EE30101010007",
    "authority": {"id": "auth-xroad-test", "name": "X-Road Test Authority", "registryCode": "70000099", "subsets": ["EU01"]},
    "echo": {"hello": "world"}
  }
  ```
- Source: observed (prelive, 2026-11-03: status, `authority.id`, `registryCode`); body shape from
  `tests/authority/xroad-echo.http`. The `authority` object is the registry row, so extra registry
  fields may appear.

### 2.2 echo: no `X-Road-Client` → 401

```bash
curl -i -X POST $BASE/provide/v1/echo \
  -H "Content-Type: application/json" \
  -H "X-Road-Id: 0a11ce5e-0002-4d1d-8f1a-0ec100000002" -d '{}'
```

- Status **401**, `code: UNAUTHORIZED`, `detail`: `X-Road-Client header is missing or malformed. Expected instance/memberClass/memberCode[/subsystemCode].`
- Source: observed (prelive, 2026-11-03).

### 2.3 echo: client not in the registry → 403

```bash
curl -i -X POST $BASE/provide/v1/echo \
  -H "Content-Type: application/json" \
  -H "X-Road-Client: EE/GOV/99999999/x" \
  -H "X-Road-Id: 0a11ce5e-0003-4d1d-8f1a-0ec100000003" -d '{}'
```

- Status **403**, `code: FORBIDDEN`, `detail`: `The X-Road client does not resolve to an active competent authority.`
- Source: observed (prelive, 2026-11-03).

### 2.4 echo: `X-Road-Id` missing → 400

Same as 2.1 without the `X-Road-Id` header.

- Status **400**, `code: MISSING_REQUIRED_HEADER`, `detail`: `X-Road-Id header is required on every X-Road request.`
- `title`: `Missing Required Header`, `type`: `https://api.efti.ee/errors/missing-required-header`.
- Source: observed (local, 2026-11-03).

### 2.5 echo: `X-Road-Id` not a UUID → 400

Same as 2.1 with `X-Road-Id: not-a-uuid`.

- Status **400**, `code: INVALID_REQUEST_ID`, `title: Invalid Request ID`, `detail`: `X-Road-Id must be a UUID. The gate maps it to its internal request id, which is consumed as a typed UUID on the cross-gate path.`
- Source: observed (prelive, 2026-11-03, via a malformed generated id).

---

## 3. Subsets

### 3.1 subsets: own entitlement → 200

```bash
curl -i $BASE/provide/v1/subsets \
  -H "X-Road-Client: EE/GOV/70000099/testSubsystem" \
  -H "X-Road-Id: 0a11ce5e-0004-4d1d-8f1a-0ec100000004"
```

- Status **200**, body exactly:
  ```json
  {"authorityId": "auth-xroad-test", "registryCode": "70000099", "subsets": ["EU01"]}
  ```
- Source: observed (prelive, 2026-11-03).

---

## 4. Transport means

### 4.1 transport-means: existence, not registered → 200

```bash
curl -i -X POST $BASE/provide/v1/transport-means \
  -H "Content-Type: application/json" \
  -H "X-Road-Client: EE/GOV/70000093/ants" \
  -H "X-Road-Id: 0a11ce5e-0005-4d1d-8f1a-0ec100000005" \
  -d '{"identifier":"VESSEL-001","scope":"existence"}'
```

- Status **200**, body exactly:
  ```json
  {"countryCode": "", "identifier": "VESSEL-001", "registered": false, "scope": "existence"}
  ```
- `registered` depends on the data of the environment: `true` once a consignment with that main
  transport means or equipment id has been uploaded.
- On the local stack `VESSEL-001` exists (uploaded by the E2E suite), so `registered` is `true` there.
- Source: observed (prelive, 2026-11-03, empty database for that id; local 2026-11-03).

### 4.2 transport-means: local, registered → 200

Same request with `"scope":"local"` (or without `scope`) after a consignment for `VESSEL-001`
exists (as in `tests/authority/xroad-transport-means.http`). The projection is shown in full in 4.5.

- Status **200**; `identifier`, `scope: "local"`, `registered`, `found` (= length of `consignments`),
  `consignments[]` with `mainTransportId`, `uil` (`gateId`, `platformId`, `datasetId`).
- Source: observed (local, 2026-11-03; see 4.5 for the full projection).

### 4.3 transport-means: missing identifier → 400

```bash
curl -i -X POST $BASE/provide/v1/transport-means \
  -H "Content-Type: application/json" \
  -H "X-Road-Client: EE/GOV/70000093/ants" \
  -H "X-Road-Id: 0a11ce5e-0006-4d1d-8f1a-0ec100000006" -d '{}'
```

- Status **400**, `code: BAD_REQUEST_GENERAL`, `detail`: `identifier is required and must be a non-empty string.`
- Source: observed (prelive, 2026-11-03).

### 4.4 transport-means: authority without `EU02` → 403

Same as 4.1 with client `EE/GOV/70000099/testSubsystem` (`EU01` only).

- Status **403**, body:
  ```json
  {"authorityId": "auth-xroad-test", "code": "FORBIDDEN_SUBSET", "deniedSubsets": ["EU02"], "detail": "A transport-means lookup returns means-of-transport data (EU02), which this authority is not permitted to access.", "permittedSubsets": ["EU01"], "status": 403, "title": "Subset Access Denied", "type": "https://api.efti.ee/errors/forbidden-subset"}
  ```
- Source: observed (local, 2026-11-03).

### 4.5 transport-means: plate seen on two datasets → 200, found 2

Needs the [test data](data/README.md).

```bash
curl -i -X POST $BASE/provide/v1/transport-means \
  -H "Content-Type: application/json" \
  -H "X-Road-Client: EE/GOV/70000093/ants" \
  -H "X-Road-Id: 0a11ce5e-0009-4d1d-8f1a-0ec100000009" \
  -d '{"identifier":"TST-001"}'
```

- Status **200**. Top level: `countryCode: ""`, `found: 2`, `identifier: "TST-001"`, `scope: "local"`,
  `consignments` (2 items, newest version first: `…0005`, then `…0001`).
- First item (`…0005`); `createdAt` is the load time and differs per environment:
  ```json
  {"acceptanceCountry": "EE", "acceptanceDate": "2026-11-03T00:00:00Z", "carriedEquipmentCategories": null, "carriedEquipmentIds": null, "createdAt": "…", "dangerousGoods": "0", "deliveryCountry": "FI", "deliveryDate": "2026-11-03T00:00:00Z", "loadingCountry": "EE", "loadingDate": "2026-11-03T00:00:00Z", "mainTransportId": "TST-001", "mainTransportType": "31", "status": "ACTIVE", "transportMode": "3", "transportRegCountry": "EE", "uil": {"datasetId": "11111111-0000-4000-8000-000000000005", "gateId": "EU-EE", "platformId": "mock"}, "unloadingCountry": "FI", "unloadingDate": "2026-11-03T00:00:00Z", "usedEquipmentCategories": null, "usedEquipmentCountries": null, "usedEquipmentIds": null}
  ```
  Absent values are rendered as explicit `null`; the second item differs in `dangerousGoods: "1"`,
  the delivery/unloading country `LV` and the 1–2 November dates.
- Source: observed (local, 2026-11-03).

### 4.6 transport-means: container on the equipment → 200, found 1

Body `{"identifier":"CONT-100","scope":"existence"}` (used equipment of `…002`): **200**,
`{"countryCode":"","identifier":"CONT-100","registered":true,"scope":"existence"}`. With `"scope":"local"`:
`found: 1`, `consignments[0].mainTransportId: "TST-002"`, `usedEquipmentIds: ["CONT-100"]`,
`usedEquipmentCategories: ["T10"]`, `usedEquipmentCountries: ["DE"]`.
Source: observed (local, 2026-11-03).

### 4.7 transport-means: carried container → 200, found 1

Body `{"identifier":"CONT-200"}` (carried on the vessel `…003`): **200**, `found: 1`,
`consignments[0].mainTransportId: "IMO9321483"`, `carriedEquipmentIds: ["CONT-200","CONT-201"]`,
`carriedEquipmentCategories: ["BPR","BPR"]`. Source: observed (local, 2026-11-03).

### 4.8 transport-means: vessel by IMO, existence → 200

Body `{"identifier":"IMO9321483","scope":"existence"}`: **200**, exactly
`{"countryCode":"","identifier":"IMO9321483","registered":true,"scope":"existence"}`.
Source: observed (local, 2026-11-03).

### 4.9 transport-means: country filter matches → 200; wrong country → not registered

Body `{"identifier":"OH-LKA","countryCode":"FI","scope":"existence"}`: **200**, `registered: true`,
`countryCode: "FI"`. Same body with `"countryCode":"EE"`: **200**, `registered: false`,
`countryCode: "EE"`. Source: observed (local, 2026-11-03).

### 4.10 transport-means: unknown identifier, local → 200, found 0

Body `{"identifier":"NO-SUCH-ID"}`: **200**, `registered: false`, `found: 0`, `consignments: []`,
`countryCode: ""`, `scope: "local"`. Source: observed (local, 2026-11-03).

### 4.11 transport-means: invalid scope → 400

Body `{"identifier":"TST-001","scope":"everywhere"}`: **400**, `code: BAD_REQUEST_GENERAL`,
`detail`: `scope must be one of 'existence', 'local' or 'allgates'. Omit it for 'local'.`
`title: Bad Request`. Source: observed (local, 2026-11-03).

### 4.12 transport-means: countryCode of the wrong type → 400

Body `{"identifier":"TST-001","countryCode":5}`: **400**, body exactly
`{"error":"Field type mismatch in body: countryCode expected string, got number"}` (the engine rejects
the type before the DSL runs, so this is not the RFC 7807 shape). Source: observed (local, 2026-11-03).

---

## 5. Dataset

### 5.1 dataset: body missing `uil`/`subsets` → 400

```bash
curl -i -X POST $BASE/provide/v1/dataset \
  -H "Content-Type: application/json" \
  -H "X-Road-Client: EE/GOV/70000093/ants" \
  -H "X-Road-Id: 0a11ce5e-0007-4d1d-8f1a-0ec100000007" -d '{}'
```

- Status **400**, `code: BAD_REQUEST_GENERAL`, `detail`: `The request body is missing or malformed. It must be a JSON object with 'uil' (object) and 'subsets' (non-empty array of subset codes).`
- Source: observed (prelive, 2026-11-03, status and start of `detail`); full text from the DSL.

---

### 5.2 dataset: valid request → 200

```bash
curl -i -X POST $BASE/provide/v1/dataset \
  -H "Content-Type: application/json" \
  -H "X-Road-Client: EE/GOV/70000093/ants" \
  -H "X-Road-Id: 0a11ce5e-0011-4d1d-8f1a-0ec100000011" \
  -d '{"uil":{"gateId":"EU-EE","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

- Status **200**; keys `consignment`, `subsets` (`["EU01"]`), `uil` (as sent) and `xml`. The content
  comes from the `mock` platform (`mock-platform/GET/v1/dataset`), not from the stored identifier row:
  locally `consignment.mainCarriageLogisticsTransportMovement.usedLogisticsTransportMeans.Id` is
  `MOCK-PLATE-1`, whatever the dataset's stored plate.
- Source: observed (local, 2026-11-03; first 300 characters of the body).

### 5.3 dataset: subset the authority does not hold → 403

Same request with `"subsets":["EU09"]`:

- Status **403**, body:
  ```json
  {"authorityId": "auth-xroad-tm", "code": "FORBIDDEN_SUBSET", "deniedSubsets": ["EU09"], "detail": "The requesting authority is not permitted to access one or more of the requested eFTI subsets.", "permittedSubsets": ["EU01", "EU02"], "status": 403, "title": "Subset Access Denied", "type": "https://api.efti.ee/errors/forbidden-subset"}
  ```
- Source: observed (local, 2026-11-03).

### 5.4 follow-up: empty body → 400

```bash
curl -i -X POST $BASE/provide/v1/follow-up \
  -H "Content-Type: application/json" \
  -H "X-Road-Client: EE/GOV/70000093/ants" \
  -H "X-Road-Id: 0a11ce5e-0012-4d1d-8f1a-0ec100000012" -d '{}'
```

- Status **400**, `code: BAD_REQUEST_GENERAL`, `detail`: `The request body must be a JSON object with 'uil' (object), 'referenceIds' (array) and 'message' (string).`
- Source: observed (local, 2026-11-03).

---

## 6. Search

The body is the criteria object itself (no `criteria` wrapper): each key is a field with
`{"operator": "EQ"|"NE", …}` and the value key named after the field (`id`, `mode`, `country`, `code`).
Answer: a JSON array of the same projection as 4.5, newest version first, with header
`x-poll-more: false` when no peer gate is still answering.

### 6.1 search: empty criteria → 502 on prelive, 200 locally

```bash
curl -i -X POST $BASE/provide/v1/search \
  -H "Content-Type: application/json" \
  -H "X-Road-Client: EE/GOV/70000099/testSubsystem" \
  -H "X-Road-Id: 0a11ce5e-0008-4d1d-8f1a-0ec100000008" -d '{}'
```

- **Known issue (prelive, 2026-11-03):** status **502**, body
  ```json
  {"code": "GATEWAY_UNAVAILABLE", "detail": "Normalising the gate's consignment search result failed in the gate's mapping layer.", "failedStep": "transport-means-normalize", "mapperStatus": 401, "status": 502, "title": "Bad Gateway", "type": "https://api.efti.ee/errors/bad-gateway"}
  ```
  Cause: xml-mapper had no `INTERNAL_SERVICE_TOKEN` and fails closed. Fix: devops MR
  `fix/xml-mapper-internal-token` (services/efti/devops!19).
- **Expected once fixed (and locally):** status **200**, a JSON array of at most the page size
  (default 20), newest first, header `x-poll-more: false`.
- Source: observed (prelive and local, 2026-11-03). `dataset` with a valid body uses the same mapper
  on prelive and is affected the same way.

### 6.2 search: by main transport id → 200, two rows

Body `{"mainTransportId":{"operator":"EQ","id":"TST-001"}}`: **200**, `x-poll-more: false`, 2 items,
`uil.datasetId` `…0005` then `…0001`. Source: observed (local, 2026-11-03).

### 6.3 search: by transport mode → 200, one row

Body `{"transportMode":{"operator":"EQ","mode":"4"}}`: **200**, 1 item, `mainTransportId: "OH-LKA"`
(`…0004`). Source: observed (local, 2026-11-03).

### 6.4 search: by dangerous goods code → 200, one row

Body `{"dangerousGoodsCode":{"operator":"EQ","code":"1"}}`: **200**, 1 item, `TST-001` (`…0001`).
Source: observed (local, 2026-11-03).

### 6.5 search: several criteria, one negated → 200, one row

Body `{"acceptanceCountry":{"operator":"NE","country":"EE"},"transportMode":{"operator":"EQ","mode":"3"}}`
(road, accepted outside EE): **200**, 1 item, `TST-002` (`…0002`). Source: observed (local, 2026-11-03).

### 6.6 search: by delivery country → 200, several rows

Body `{"deliveryCountry":{"operator":"EQ","country":"EE"}}`: **200**; locally 5 items, newest first:
`WAGON-77`, `OH-LKA`, `IMO9321483`, `TST-002`, `VESSEL-001` (the last comes from the E2E suite).
Source: observed (local, 2026-11-03).
