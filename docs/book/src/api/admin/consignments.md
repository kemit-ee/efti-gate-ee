# Consignments

What platforms registered at this gate: the current rows, the version history of one dataset, a
soft delete, and two reporting views (counts and an authority summary).

All routes are on ruuter (G1 :27086, G2 :28086) behind the [admin guard](./index.md#the-admin-guard).
Get `$TOKEN` with [dev-login](./auth.md#post-authdev-login).

| Route | Purpose | Data source |
|---|---|---|
| [`GET /admin/v1/consignments`](#get-adminv1consignments) | Current version of every dataset, newest first | `consignments` table |
| [`DELETE /admin/v1/consignments/{id}`](#delete-adminv1consignmentsid) | Soft-delete one dataset | `consignments` table |
| [`GET /admin/v1/consignment-history`](#get-adminv1consignment-history) | All versions of one dataset, live and archived | `consignments` + archive DB |
| [`GET /admin/v1/consignment-counts`](#get-adminv1consignment-counts) | Counts per gate, platform, status | read model `rm_consignment_counts` |
| [`GET /admin/v1/consignment-summary`](#get-adminv1consignment-summary) | Counts one authority may see, by subset dimension | read model `rm_consignment_summary` |

## Common responses

| Status | When | Body |
|---|---|---|
| 401 | no or bad token, no active user row | RFC 7807 `unauthorized` |
| 503 | TIM or ReSql unreachable during the guard check | RFC 7807 `service-unavailable` |
| 502 | the ReSql call failed (also: a non-numeric `limit` or `offset`) | `{"error":"Data layer unavailable"}` |

The dataset used below, `73314152-cbe2-4cb5-83f0-d19965bac2e6`, was uploaded for this page by the
`mock` platform (dev key `X-Api-Key: mock-secret-key`, public in the repository), twice, with two
different transport ids:

```bash
DS=$(cat /proc/sys/kernel/random/uuid)
curl -s -i -X POST "http://127.0.0.1:27086/platforms/v1/consignments/$DS" \
  -H 'Content-Type: text/xml' -H 'X-Api-Key: mock-secret-key' \
  -d '<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>ADMIN-DOC-01</ID></MainCarriageTransportMeansIDParameterScope><MainCarriageModeCodeParameterScope><TransportModeParameterCode>3</TransportModeParameterCode></MainCarriageModeCodeParameterScope><CarrierAcceptanceCountryParameterScope><CountryID>EE</CountryID></CarrierAcceptanceCountryParameterScope></ParameterIDSetCriteria>'
```

```http
HTTP/1.1 201 Created
content-type: application/json
content-length: 193
{"createdAt":"2026-11-03T16:01:43.063776Z","datasetId":"73314152-cbe2-4cb5-83f0-d19965bac2e6","gateId":"G1","platformId":"mock","rowId":"43fcd636-d88f-4db2-a044-95492b622e87","status":"ACTIVE"}
```

The second upload used `ADMIN-DOC-02`. See [Upload identifiers (REST)](../platforms/upload-rest.md).

---

## `GET /admin/v1/consignments`

`GET /admin/v1/consignments` · admin JWT · source `DSL/Ruuter/admin/GET/v1/consignments.yml` → `get_consignments.sql`

The current version of every dataset that is not deleted, newest first. One row per
(`platformId`, `datasetId`). Includes the stored `xml`.

| Query parameter | Required | Type | Meaning |
|---|---|---|---|
| `limit` | no | number | Default 100, clamped to 0..1000 |
| `offset` | no | number | Default 0 |

There are no filters. The route sends `criteria: {}`.

Row fields: `rowId`, `datasetId`, `platformId`, `gateId`, `status` (`ACTIVE` or `INACTIVE`),
`xml` (the uploaded `ParameterIDSetCriteria`), `createdAt`, and the search columns
`transportMode`, `acceptanceDate`, `acceptanceCountry`, `deliveryDate`, `deliveryCountry`,
`dangerousGoods`, `mainTransportId`, `mainTransportType`, `transportRegCountry`, `loadingDate`,
`loadingCountry`, `unloadingDate`, `unloadingCountry`, `usedEquipmentIds`,
`usedEquipmentCategories`, `usedEquipmentCountries`, `usedEquipmentSeq`, `carriedEquipmentIds`,
`carriedEquipmentCategories`, `carriedEquipmentSeq`. See [Search criteria](../../data/search-criteria.md).

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/consignments?limit=2" -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 6008
[{"acceptanceCountry":"EE","acceptanceDate":null,"carriedEquipmentCategories":[],"carriedEquipmentIds":[],"carriedEquipmentSeq":[],"createdAt":"2026-11-03T16:01:50.961816Z","dangerousGoods":null,"datasetId":"73314152-cbe2-4cb5-83f0-d19965bac2e6","deliveryCountry":null,"deliveryDate":null,"gateId":"G1","loadingCountry":null,"loadingDate":null,"mainTransportId":"ADMIN-DOC-02","mainTransportType":null,"platformId":"mock","rowId":"0df2b069-1407-4ca5-a92c-51a0f767907e","status":"ACTIVE","transportMode":"3","transportRegCountry":null,"unloadingCountry":null,"unloadingDate":null,"usedEquipmentCategories":[],"usedEquipmentCountries":[],"usedEquipmentIds":[],"usedEquipmentSeq":[],"xml":"<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>ADMIN-DOC-02</ID>…</ParameterIDSetCriteria>"},{"acceptanceCountry":"DE","acceptanceDate":"2021-09-24T00:00:00Z",…,"mainTransportId":"VESSEL-001",…}]
```

Only `ADMIN-DOC-02` appears: the first upload is an older version of the same dataset.

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/consignments?limit=abc" -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
content-length: 34

{"error":"Data layer unavailable"}
```

Audit: `consignment.list` with `{"count","limit","offset"}`.

Notes:
- The list has no total count and no filter. With more than 1000 datasets, page with `offset`.
- The `xml` makes rows large (about 3 KB each for a full FTI004 criteria block).

---

## `DELETE /admin/v1/consignments/{id}`

`DELETE /admin/v1/consignments/{id}?platformId=…&gateId=…` · admin JWT · source `DSL/Ruuter/admin/DELETE/v1/consignments.yml` → `soft_delete_consignment.sql`

Soft-deletes one dataset: copies its latest row with `status = DELETED`. Searches, dataset requests
and the list no longer see it. The history keeps every version.

The three values together are the dataset's [UIL](../../data/uil.md).

| Path / query parameter | Required | Type | Meaning |
|---|---|---|---|
| `id` (path) | yes | uuid | `datasetId` |
| `platformId` (query) | yes | string | Platform that uploaded it |
| `gateId` (query) | yes | string | Gate in the UIL. Must match the stored `gateId`. |

| Status | When | Body |
|---|---|---|
| 204 | deleted | none, but `content-length: 2` (see Notes) |
| 400 | `platformId` or `gateId` missing or empty | `{"error":"Required UIL query parameters: platformId, gateId"}` |
| 404 | no such dataset for this platform, the `gateId` does not match, or it is already deleted | `{"error":"Not Found"}` |
| 500 | ReSql error (also: `id` not a UUID), or the new row is not `DELETED` | `{"error":"Consignment deletion could not be verified"}` |

### Missing `gateId`

```bash
curl -s -i -X DELETE "http://127.0.0.1:27086/admin/v1/consignments/$DS?platformId=mock" \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 61

{"error":"Required UIL query parameters: platformId, gateId"}
```

### Wrong gate in the UIL

```bash
curl -s -i -X DELETE "http://127.0.0.1:27086/admin/v1/consignments/$DS?platformId=mock&gateId=G2" \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 404 Not Found
content-type: application/json
content-length: 21

{"error":"Not Found"}
```

`platformId=other&gateId=G1` gives the same 404.

### Delete

```bash
curl -s -i -X DELETE "http://127.0.0.1:27086/admin/v1/consignments/$DS?platformId=mock&gateId=G1" \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 204 No Content
content-type: application/json
content-length: 2

```

### Delete again

```http
HTTP/1.1 404 Not Found
content-type: application/json
content-length: 21

{"error":"Not Found"}
```

### Not a UUID

```bash
curl -s -i -X DELETE "http://127.0.0.1:27086/admin/v1/consignments/abc?platformId=mock&gateId=G1" \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 500 Internal Server Error
content-type: application/json
content-length: 54

{"error":"Consignment deletion could not be verified"}
```

Audit: `consignment.delete` with `resourceId` = the dataset id and
`{"dataset_id":"…","triggered_by":"admin"}`.

Notes:
- **204 with `content-length: 2`.** Same defect as the other `return: ""` routes; see
  [Known issues](../../reference/known-issues.md).
- A malformed id answers 500, not 400 or 404.
- The platform can upload the same `datasetId` again later; that makes it `ACTIVE` again.

---

## `GET /admin/v1/consignment-history`

`GET /admin/v1/consignment-history?datasetId=…` · admin JWT · source `DSL/Ruuter/admin/GET/v1/consignment-history.yml` → `get_consignment_history.sql`, `archive/get_archived_consignments.sql`

Every version of one dataset, newest first: `live` from the gate database, `archived` from the
separate archive database (rows the `archive-consignments` job moved there). Metadata only, no `xml`.

| Query parameter | Required | Type | Meaning |
|---|---|---|---|
| `datasetId` | yes | string (uuid) | Dataset to look up |
| `limit` | no | number | Default 100, max 1000. Applied to `live` and `archived` separately. |
| `offset` | no | number | Default 0. Applied to each side separately. |

| Status | When | Body |
|---|---|---|
| 200 | always when `datasetId` is given | `{"datasetId","live":[…],"archived":[…]}` |
| 400 | `datasetId` missing or empty | RFC 7807-shaped `BAD_REQUEST_GENERAL`, `detail: "Query parameter datasetId is required."` |

`live` and `archived` items: `rowId`, `datasetId`, `platformId`, `gateId`, `status` (`ACTIVE`,
`INACTIVE`, `DELETED`), `transportMode`, `mainTransportId`, `mainTransportType`,
`usedEquipmentIds`, `carriedEquipmentIds`, `createdAt`.

### Two versions

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/consignment-history?datasetId=$DS" \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 713
{"archived":[],"datasetId":"73314152-cbe2-4cb5-83f0-d19965bac2e6","live":[{"carriedEquipmentIds":[],"createdAt":"2026-11-03T16:01:50.961816Z","datasetId":"73314152-cbe2-4cb5-83f0-d19965bac2e6","gateId":"G1","mainTransportId":"ADMIN-DOC-02","mainTransportType":null,"platformId":"mock","rowId":"0df2b069-1407-4ca5-a92c-51a0f767907e","status":"ACTIVE","transportMode":"3","usedEquipmentIds":[]},{"carriedEquipmentIds":[],"createdAt":"2026-11-03T16:01:43.063776Z","datasetId":"73314152-cbe2-4cb5-83f0-d19965bac2e6","gateId":"G1","mainTransportId":"ADMIN-DOC-01","mainTransportType":null,"platformId":"mock","rowId":"43fcd636-d88f-4db2-a044-95492b622e87","status":"ACTIVE","transportMode":"3","usedEquipmentIds":[]}]}
```

### After the delete

```bash
curl -s "http://127.0.0.1:27086/admin/v1/consignment-history?datasetId=$DS&limit=1" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{"archived":[],"datasetId":"73314152-cbe2-4cb5-83f0-d19965bac2e6","live":[{"carriedEquipmentIds":[],"createdAt":"2026-11-03T16:02:06.656020Z","datasetId":"73314152-cbe2-4cb5-83f0-d19965bac2e6","gateId":"G1","mainTransportId":"ADMIN-DOC-02","mainTransportType":null,"platformId":"mock","rowId":"f2db6117-4ea3-456b-acf8-d00e48ce06aa","status":"DELETED","transportMode":"3","usedEquipmentIds":[]}]}
```

### Missing `datasetId`

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/consignment-history" -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 163

{"code":"BAD_REQUEST_GENERAL","detail":"Query parameter datasetId is required.","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

### Unknown or malformed id

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/consignment-history?datasetId=abc" \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 43

{"archived":[],"datasetId":"abc","live":[]}
```

An unknown UUID gives the same shape with empty arrays.

Notes:
- The route does not check the ReSql status of either call. A failing query (here: `abc` is not a
  UUID) shows up as an empty array, not as an error. An unreachable archive database would look
  the same as "nothing archived".
- No audit row is written for this read.
- The history spans all platforms: two platforms with the same `datasetId` both show up.

---

## Read models (counts and summary)

`consignment-counts` and `consignment-summary` do not query `consignments`. They read
pre-computed snapshots (ADR-012). The job `POST /ops/v1/refresh-read-models` builds a new
snapshot ("generation") and publishes it; the routes read the latest published generation. In a
deployment CronManager runs the job every 15 minutes (`DSL/CronManager/efti-ops.yaml`,
`efti_gate_read_models_refresh`). See [Scheduled jobs](../operations/jobs.md).

So:

- The numbers can be up to one refresh interval old.
- **Before the first refresh both routes return `[]`.** The local two-gate stack has no CronManager,
  so nothing refreshes them unless you call the job.
- They count only current `ACTIVE` datasets (the summary) or current datasets per status (the
  counts). `DELETED` is never counted.

Freshly started G2, never refreshed:

```bash
curl -s -i http://127.0.0.1:28086/admin/v1/consignment-counts -H "Authorization: Bearer $LV_TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 2

[]
```

Refresh G1 (dev ops token, public in the repository):

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/refresh-read-models \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
  -H 'Content-Type: application/json' -d '{}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 41

{"generation":5,"purgedRows":31,"rows":1}
```

---

## `GET /admin/v1/consignment-counts`

`GET /admin/v1/consignment-counts` · admin JWT · source `DSL/Ruuter/admin/GET/v1/consignment-counts.yml` → `get_consignment_counts.sql`

| Query parameter | Required | Type | Meaning |
|---|---|---|---|
| `gateId` | no | string | Only this gate |
| `platformId` | no | string | Only this platform |
| `status` | no | `ACTIVE` or `INACTIVE` | Only this status |
| `limit` | no | number | Default 100, clamped to 0..1000 |
| `offset` | no | number | Default 0 |

| Status | When | Body |
|---|---|---|
| 200 | always (also `[]` before the first refresh) | array of count rows |
| 400 | `status` is set and not `ACTIVE` or `INACTIVE` | RFC 7807-shaped `BAD_REQUEST_GENERAL` |

Row: `generation`, `gateId`, `platformId`, `status`, `consignmentCount`, `createdAt` (when the
snapshot was built). Ordered by gate, platform, status.

```bash
curl -s -i http://127.0.0.1:27086/admin/v1/consignment-counts -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 134
[{"consignmentCount":39,"createdAt":"2026-11-03T16:02:37.038267Z","gateId":"G1","generation":5,"platformId":"mock","status":"ACTIVE"}]
```

All filters:

```bash
curl -s "http://127.0.0.1:27086/admin/v1/consignment-counts?gateId=G1&platformId=mock&status=ACTIVE&limit=10&offset=0" \
  -H "Authorization: Bearer $TOKEN"
```

```json
[{"consignmentCount":39,"createdAt":"2026-11-03T16:02:37.038267Z","gateId":"G1","generation":5,"platformId":"mock","status":"ACTIVE"}]
```

`?status=INACTIVE` returns `[]` (no inactive datasets locally).

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/consignment-counts?status=DELETED" -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 175

{"code":"BAD_REQUEST_GENERAL","detail":"Query parameter status must be ACTIVE or INACTIVE.","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

Audit: `consignment-counts.list` with `{"count","limit","offset"}`.

---

## `GET /admin/v1/consignment-summary`

`GET /admin/v1/consignment-summary?authorityId=…` · admin JWT · source `DSL/Ruuter/admin/GET/v1/consignment-summary.yml` → `get_consignment_summary.sql`

Shows what one competent authority would be entitled to see in aggregate. The route reads the
authority's `subsets` from the registry and returns only:

| `subset` | `dimension` | Returned when |
|---|---|---|
| `""` | `total` | always |
| `EU02` | `dangerous_goods` | the authority has `EU02` |
| `EU03` | `loading_country`, `unloading_country` | the authority has `EU03` |
| `EU04` | `transport_mode`, `main_transport_type`, `transport_reg_country` | the authority has `EU04` |

`dimValue` is the value counted (`""` when the column is empty). Counts are per registration day
(the `created_at` date of the current row) and summed over the requested range.

| Query parameter | Required | Type | Meaning |
|---|---|---|---|
| `authorityId` | yes | string | Authority id from the registry, any case |
| `from` | no | `YYYY-MM-DD` | First registration day, inclusive |
| `to` | no | `YYYY-MM-DD` | Last registration day, inclusive. Must not be before `from`. |
| `dimension` | no | string | Only this dimension (also filters out `total`) |
| `limit` | no | number | Default 100, clamped to 0..1000 |
| `offset` | no | number | Default 0 |

| Status | When | Body |
|---|---|---|
| 200 | authority exists (also `[]` before the first refresh or for an empty range) | array of `{subset, dimension, dimValue, consignmentCount}` |
| 400 | `authorityId` missing | `BAD_REQUEST_GENERAL`, `detail: "Query parameter authorityId is required."` |
| 400 | `from`/`to` not `YYYY-MM-DD`, or `from` after `to` | `BAD_REQUEST_GENERAL`, `detail: "Query parameters from and to must be YYYY-MM-DD dates with from not after to."` |
| 404 | no registry file for `authorityId` | `{"error":"Not Found"}` |
| 502 | registry unreachable, ReSql error (also: a well-formed but impossible date like `2026-13-45`) | `{"error":"Data layer unavailable"}` |

### An authority with EU01 and EU02

```bash
curl -s "http://127.0.0.1:27086/admin/v1/consignment-summary?authorityId=auth-xroad-tm" \
  -H "Authorization: Bearer $TOKEN"
```

```json
[{"consignmentCount":39,"dimValue":"","dimension":"total","subset":""},{"consignmentCount":18,"dimValue":"","dimension":"dangerous_goods","subset":"EU02"},{"consignmentCount":5,"dimValue":"0","dimension":"dangerous_goods","subset":"EU02"},{"consignmentCount":1,"dimValue":"1","dimension":"dangerous_goods","subset":"EU02"},{"consignmentCount":15,"dimValue":"2","dimension":"dangerous_goods","subset":"EU02"}]
```

`EU01` has no dimension, so only the total and `EU02` appear.

### One dimension

```bash
curl -s "http://127.0.0.1:27086/admin/v1/consignment-summary?authorityId=auth-summary-eu03&dimension=loading_country" \
  -H "Authorization: Bearer $TOKEN"
```

```json
[{"consignmentCount":18,"dimValue":"","dimension":"loading_country","subset":"EU03"},{"consignmentCount":1,"dimValue":"DE","dimension":"loading_country","subset":"EU03"},{"consignmentCount":2,"dimValue":"EE","dimension":"loading_country","subset":"EU03"},{"consignmentCount":16,"dimValue":"FI","dimension":"loading_country","subset":"EU03"},{"consignmentCount":1,"dimValue":"LV","dimension":"loading_country","subset":"EU03"},{"consignmentCount":1,"dimValue":"SE","dimension":"loading_country","subset":"EU03"}]
```

### No subsets

```bash
curl -s "http://127.0.0.1:27086/admin/v1/consignment-summary?authorityId=auth-summary-none" \
  -H "Authorization: Bearer $TOKEN"
```

```json
[{"consignmentCount":39,"dimValue":"","dimension":"total","subset":""}]
```

### Date range

```bash
curl -s "http://127.0.0.1:27086/admin/v1/consignment-summary?authorityId=auth-xroad-tm&from=2020-01-01&to=2020-12-31" \
  -H "Authorization: Bearer $TOKEN"
```

```json
[]
```

`from=2026-11-03&to=2026-11-03` (the day the data was loaded) returns the same numbers as no range.

### Paging

```bash
curl -s "http://127.0.0.1:27086/admin/v1/consignment-summary?authorityId=auth-xroad-tm&limit=2&offset=1" \
  -H "Authorization: Bearer $TOKEN"
```

```json
[{"consignmentCount":18,"dimValue":"","dimension":"dangerous_goods","subset":"EU02"},{"consignmentCount":5,"dimValue":"0","dimension":"dangerous_goods","subset":"EU02"}]
```

### Errors

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/consignment-summary" -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 165

{"code":"BAD_REQUEST_GENERAL","detail":"Query parameter authorityId is required.","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/consignment-summary?authorityId=auth-xroad-tm&from=03.11.2026" \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 202

{"code":"BAD_REQUEST_GENERAL","detail":"Query parameters from and to must be YYYY-MM-DD dates with from not after to.","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

`from=2026-12-01&to=2026-01-01` gives the same 400.

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/consignment-summary?authorityId=auth-nope" \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 404 Not Found
content-type: application/json
content-length: 21

{"error":"Not Found"}
```

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/consignment-summary?authorityId=auth-xroad-tm&from=2026-13-45" \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
content-length: 34

{"error":"Data layer unavailable"}
```

Audit: `consignment-summary.read`, `resource: authority`, `resourceId` = `authorityId`, details
with `authority_id`, `from`, `to`, `dimension`, `count`.

Notes:
- The declaration says the authority and its subsets are "read from the source table". They are
  read from the `registry` service (`/authorities/<id>.json`); there is no authorities table
  (ADR-011).
- The entitlement comes from the registry, never from the read model, so a registry change applies
  at once. The numbers only change after the next refresh.
