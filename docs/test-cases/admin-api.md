# Admin API — `/admin/v1/**`

All routes need an administrator JWT (`Authorization: Bearer <token>`); the three registries are
read-only. Local stack only: a token comes from `POST /auth/dev-login` (404 unless
`DEV_LOGIN_ENABLED=true`):

```bash
BASE=http://localhost:8086
TOKEN=$(curl -s -X POST $BASE/auth/dev-login -H "Content-Type: application/json" \
  -d '{"personalCode":"60001019906","firstName":"Super","lastName":"Admin"}' | jq -r .token)
```

The data cases need the [test data](data/README.md). All were run on 2026-11-03 against the local
compose stack with the test data loaded (**observed (local)**); counts include the fixtures the E2E
suite uploads, so they say "at least".

### A.1 consignments: no token → 401

`curl -i $BASE/admin/v1/consignments` — **401**.

### A.2 consignments: list → 200

`curl -s "$BASE/admin/v1/consignments?limit=100" -H "Authorization: Bearer $TOKEN"` — **200**, a JSON
array of consignment rows (keys include `rowId`, `datasetId`, `gateId`, `platformId`, `mainTransportId`,
`status`, the date/country columns, the equipment arrays and `xml`) that contains the six test
datasets (8 rows locally: the six plus the E2E fixtures).

### A.3 consignment-history: versions of one dataset → 200

`…/admin/v1/consignment-history?datasetId=11111111-0000-4000-8000-000000000001` — **200**,
`{"archived": [], "datasetId": "11111111-0000-4000-8000-000000000001", "live": [{…}]}`; `live` has
one item with `mainTransportId: "TST-001"`, `status: "ACTIVE"`, `transportMode: "3"` (metadata only,
no `xml`).

### A.4 consignment-history: no datasetId → 400

`…/admin/v1/consignment-history` — **400**, `code: BAD_REQUEST_GENERAL`,
`detail: "Query parameter datasetId is required."`.

### A.5 consignment-counts: after a read-model refresh → 200

Refresh first (`POST $BASE/ops/v1/refresh-read-models`, bearer `dev-archive-ops-token-change-me`),
then `…/admin/v1/consignment-counts?status=ACTIVE` — **200**, an array of
`{"consignmentCount", "createdAt", "gateId", "generation", "platformId", "status": "ACTIVE"}`; locally
`EU-EE`/`mock` is 7 (the six plus `VESSEL-001`) and `EU-MOCK`/`mock` is 1.

### A.6 consignment-counts: status DELETED → 400

`…/admin/v1/consignment-counts?status=DELETED` — **400**, `detail: "Query parameter status must be ACTIVE or INACTIVE."`.

### A.7 registries are read-only → 405

`curl -i -X POST $BASE/admin/v1/gates -H "Authorization: Bearer $TOKEN"` — **405** (no write route
exists for gates, platforms and authorities).

### A.8 gates/own → 200

`…/admin/v1/gates/own` — **200**, the own gate's registry row: `countryCode: "EE"`, `eDeliveryCert`
(PEM), … (dev registry).
