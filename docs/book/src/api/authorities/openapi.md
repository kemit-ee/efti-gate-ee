# openapi

Returns the OpenAPI 3.0 description of the X-Road provider API. The gate's Security Server reads
it when the service is registered, without any `X-Road-*` header, so this route is public.

`GET /xroad/provide/openapi` · on ruuter-xroad (G1 :27087, G2 :28087) · no authentication ·
source `DSL/Ruuter-xroad/xroad/GET/provide/openapi.yml`

## Request

No headers, no parameters, no body.

## Responses

| Status | When | Body |
|---|---|---|
| 200 | always | the OpenAPI document, `application/json` (about 34 KB) |
| 405 | any method other than GET | `{"allow":["GET"],"error":"Method Not Allowed"}` |

The document has the top-level keys `openapi` (`3.0.3`), `info`, `servers`, `tags`, `paths`
and `components`.

| Part | Content |
|---|---|
| `servers` | one entry, `url: /xroad/provide` (the provider-side base) |
| `paths` | `/openapi`, `/v1/echo`, `/v1/subsets`, `/v1/search`, `/v1/transport-means`, `/v1/dataset`, `/v1/follow-up` |
| `components.parameters` | `XRoadClient`, `XRoadId`, `XRoadUserId` |
| `components.headers` | `XPollMore`, `XSearchId` |
| `components.responses` | `Unauthorized`, `Forbidden`, `MissingHeader`, `GatewayUnavailable` |
| `components.schemas` | `SearchCriteria`, `StringCriterion`, `DateCriterion`, `Operator`, `PollRequest`, `TransportMeansRequest`, `TransportMeansResponse`, `TransportMeansExistenceResponse`, `TransportMeansConsignment`, `DatasetRequest`, `FollowUpRequest`, `Uil`, `Subset`, `Authority`, `ProblemDetails`, `SubsetProblemDetails`, `GatewayProblemDetails`, `ErrorCode` |

## Examples

### Fetch the document

```bash
curl -i http://127.0.0.1:27087/xroad/provide/openapi
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 34460

{"components":{"headers":{"XPollMore":{"description":"Present and `true` while neighbouring gates are still responding. …
```

### List the paths

```bash
curl -s http://127.0.0.1:27087/xroad/provide/openapi \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["openapi"], d["servers"][0]["url"]); print(list(d["paths"]))'
```

```text
3.0.3 /xroad/provide
['/openapi', '/v1/dataset', '/v1/echo', '/v1/follow-up', '/v1/search', '/v1/subsets', '/v1/transport-means']
```

G2 (`http://127.0.0.1:28087/xroad/provide/openapi`) returns the same document.
The title says "eFTI Gate (EE)" on both, because the file is not templated per gate.

### Wrong method

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/openapi
```

```http
HTTP/1.1 405 Method Not Allowed
content-type: application/json
allow: GET

{"allow":["GET"],"error":"Method Not Allowed"}
```

## Notes

- The source of truth is `docs/developer/x_road_openapi.yaml`. `scripts/sync-xroad-openapi.py`
  generates the DSL file from it and rewrites `servers` to the provider-side base. Do not edit the
  DSL file by hand.
- The document is hand-written, not generated from the routes. Where it differs from what the
  routes do, the route pages in this chapter say so in their Notes. Known differences:
  - `follow-up`: the document says `referenceIds` is optional, `files` is an array of strings,
    the success status is `200`, and "contract only - the route is not implemented". The route
    requires `referenceIds`, takes `files` as objects, answers `201`, and is implemented.
  - `StringCriterion.operator` allows `LT`/`LE`/`GT`/`GE`; for non-date fields the gate only
    evaluates `EQ` and `NE` ([Search criteria](../../data/search-criteria.md)).
  - `StringCriterion.mode` lists `1`…`5`; the codes the gate uses are `1`, `2`, `3`, `4`, `8`.
  - `info.description` says a hard downstream outage surfaces as `500`. The DSL comments say
    that since Ruuter 0.9.15-rc it is a `502` with `transportError`. Not run locally (it needs a
    stopped core).
  - Error media type: the document says `application/json`; guard errors are sent as
    `application/problem+json`.
  - `dataset` and `follow-up` do not list `409 DUPLICATE_REQUEST_ID`; `follow-up` does not
    list `400`.
- Postman: **1. CA -> Gate / Identity guard / openapi (public)**.
- The developer mock does not serve an OpenAPI document (`GET /developer/v1/openapi` is `404`).
