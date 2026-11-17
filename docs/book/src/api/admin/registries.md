# Gates, platforms, authorities

Read-only views of the three registries: peer gates, freight platforms and competent authorities.
The admin UI shows them. Nothing here can change a registry.

All routes are on ruuter (G1 :27086, G2 :28086) behind the [admin guard](./index.md#the-admin-guard).
Get `$TOKEN` with [dev-login](./auth.md#post-authdev-login).

| Route | Returns | Source |
|---|---|---|
| [`GET /admin/v1/gates`](#get-adminv1gates) | all gates, paged | `DSL/Ruuter/admin/GET/v1/gates.yml` |
| [`GET /admin/v1/gates/{id}`](#get-adminv1gatesid) | one gate | same file |
| [`GET /admin/v1/gates/own`](#get-adminv1gatesown) | this gate's own entry | `DSL/Ruuter/admin/GET/v1/gates/own.yml` |
| [`GET /admin/v1/platforms`](#get-adminv1platforms) | all platforms, paged, `apiKey` removed | `DSL/Ruuter/admin/GET/v1/platforms.yml` |
| [`GET /admin/v1/platforms/{id}`](#get-adminv1platformsid) | one platform, `apiKey` removed | same file |
| [`GET /admin/v1/authorities`](#get-adminv1authorities) | all authorities, paged | `DSL/Ruuter/admin/GET/v1/authorities.yml` |
| [`GET /admin/v1/authorities/{id}`](#get-adminv1authoritiesid) | one authority | same file |

## Where the data comes from

The registries are YAML files in `registry/` (ADR-011). When `registry-build` runs (at every start)
`scripts/registry-to-json.py` validates them and writes JSON, which the `registry` container serves
(`/gates.json`, `/gates/<id lower>.json`, and the same for `platforms` and `authorities`). There
are no registry tables in the database and no HTTP write route. To change an entry: edit the YAML,
open a PR, run `docker compose up -d registry-build`. See `registry/README.md`.

Every `POST`, `PUT` or `DELETE` on these paths answers 405:

```bash
curl -s -i -X POST http://127.0.0.1:27086/admin/v1/platforms \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"id":"x"}'
```

```http
HTTP/1.1 405 Method Not Allowed
content-type: application/json
allow: GET
content-length: 46

{"allow":["GET"],"error":"Method Not Allowed"}
```

`DELETE /admin/v1/gates/G2` gives the same 405. The 405 comes before the guard: it is returned
even without a token.

## Paging and ids

The list routes read the whole registry document and cut the page in the DSL:

| Query parameter | Required | Type | Meaning |
|---|---|---|---|
| `limit` | no | number | Default 20, clamped to 0..1000. A value that is not a number counts as 0 (empty page). |
| `offset` | no | number | Default 0. Negative or not a number counts as 0. |

Order is the order of `gates.json` etc. Locally that is alphabetical by id.

Ids in the path are matched case-insensitively: `G2` and `g2` both hit
`/gates/g2.json`.

## Common responses

| Status | When | Body |
|---|---|---|
| 401 | no or bad token, no active user row | RFC 7807 `unauthorized` |
| 503 | TIM or ReSql unreachable during the guard check | RFC 7807 `service-unavailable` |
| 404 | single mode: no registry file for this id | `{"error":"Not Found"}` |
| 502 | the registry is unreachable, answers non-200, or the document has no `id` | `{"error":"Data layer unavailable"}` |

The 502 cases were not run locally: they need the registry container to be down.

In the examples below, PEM certificates are shortened to `-----BEGIN CERTIFICATE-----\n…`.

---

## `GET /admin/v1/gates`

`GET /admin/v1/gates` · admin JWT · `registry` `/gates.json`

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Gate id, for example `G1` |
| `countryCode` | string | Two-letter country code |
| `eDeliveryUrl` | string | The peer's AS4 endpoint |
| `eDeliveryCert` | string | PEM certificate the gate expects on AS4 messages from this peer |
| `tlsCert` | string | PEM, only when set in the YAML |
| `status` | string | `ONLINE` or `DISABLED` |

Nothing is removed. Gate entries carry no secret.

```bash
curl -s -i http://127.0.0.1:27086/admin/v1/gates -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 2471
[{"countryCode":"XA","eDeliveryCert":"-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----\n","eDeliveryUrl":"http://g1-edelivery:8081/services/msh","id":"G1","status":"ONLINE"},{"countryCode":"XB","eDeliveryCert":"-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----\n","eDeliveryUrl":"http://g2-edelivery:8081/services/msh","id":"G2","status":"ONLINE"}]
```

Page 2 of size 1:

```bash
curl -s "http://127.0.0.1:27086/admin/v1/gates?limit=1&offset=1" -H "Authorization: Bearer $TOKEN"
```

```json
[{"countryCode":"XB","eDeliveryCert":"-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----\n","eDeliveryUrl":"http://g2-edelivery:8081/services/msh","id":"G2","status":"ONLINE"}]
```

`?limit=0` and `?limit=abc&offset=-5` both return `[]`.

Audit: `gate.list` with `{"count","limit","offset"}`.

## `GET /admin/v1/gates/{id}`

`GET /admin/v1/gates/{id}` · admin JWT · `registry` `/gates/<id lower>.json`

| Path parameter | Required | Type | Meaning |
|---|---|---|---|
| `id` | yes | string | Gate id, any case |

```bash
curl -s -i http://127.0.0.1:27086/admin/v1/gates/G2 -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 1234
{"countryCode":"XB","eDeliveryCert":"-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----\n","eDeliveryUrl":"http://g2-edelivery:8081/services/msh","id":"G2","status":"ONLINE"}
```

```bash
curl -s -i http://127.0.0.1:27086/admin/v1/gates/EU-XX -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 404 Not Found
content-type: application/json
content-length: 21

{"error":"Not Found"}
```

Audit: `gate.read` with `resourceId` = the id as given in the path.

## `GET /admin/v1/gates/own`

`GET /admin/v1/gates/own` · admin JWT · `registry` `/gates/<OWN_GATE_ID lower>.json`

This gate's own entry. The id comes from the `OWN_GATE_ID` constant, not from the request.

```bash
curl -s -i http://127.0.0.1:27086/admin/v1/gates/own -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 1234
{"countryCode":"XA","eDeliveryCert":"-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----\n","eDeliveryUrl":"http://g1-edelivery:8081/services/msh","id":"G1","status":"ONLINE"}
```

On G2 (`http://127.0.0.1:28086/admin/v1/gates/own`, with a token from G2's dev-login) the
same call returns the `G2` entry.

| Status | When | Body |
|---|---|---|
| 200 | found | gate entry |
| 404 | the registry has no file for `OWN_GATE_ID` (misconfiguration) | `{"error":"Not Found"}` |
| 502 | registry unreachable | `{"error":"Data layer unavailable"}` |

Notes:

- `gates/own` writes **no** audit row, unlike every other read on this page.

---

## `GET /admin/v1/platforms`

`GET /admin/v1/platforms` · admin JWT · `registry` `/platforms.json`

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Platform id, for example `mock` |
| `baseUrl` | string | Where the gate calls the platform (REST) or its AS4 endpoint |
| `headers` | object | Extra headers the gate sends **to** the platform on every call |
| `eDeliveryCert` | string | PEM, for platforms that talk AS4 |
| `tlsCert` | string | PEM, only when set |
| `status` | string | `ONLINE` or `DISABLED`. Only `ONLINE` platforms can authenticate. |
| `hasApiKey` | boolean | Added by the route: `true` when the registry entry has an `apiKey` |

**Redacted:** the `apiKey` field (the key a platform presents in `X-Api-Key` to the gate) is
removed from every response and replaced by `hasApiKey`. **Not redacted:** `headers` is returned as
is. See the note below.

```bash
curl -s -i http://127.0.0.1:27086/admin/v1/platforms -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 1391
[{"baseUrl":"http://ruuter:8086/mock-platform","hasApiKey":true,"headers":{"X-Api-Key":"mock-secret-key"},"id":"mock","status":"ONLINE"},{"baseUrl":"http://g1-edelivery:8081/services/msh","eDeliveryCert":"-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----\n","hasApiKey":false,"headers":{},"id":"mock-edelivery","status":"ONLINE"}]
```

Audit: `platform.list` with `{"count","limit","offset"}`.

## `GET /admin/v1/platforms/{id}`

`GET /admin/v1/platforms/{id}` · admin JWT · `registry` `/platforms/<id lower>.json`

```bash
curl -s -i http://127.0.0.1:27086/admin/v1/platforms/mock -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 135

{"baseUrl":"http://ruuter:8086/mock-platform","hasApiKey":true,"headers":{"X-Api-Key":"mock-secret-key"},"id":"mock","status":"ONLINE"}
```

`/platforms/MOCK` returns the same. Unknown id:

```bash
curl -s -i http://127.0.0.1:27086/admin/v1/platforms/nope -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 404 Not Found
content-type: application/json
content-length: 21

{"error":"Not Found"}
```

Audit: `platform.read` with `resourceId` = the id.

### Notes

- **The `headers` map is not redacted.** It holds the credentials the gate sends to the platform.
  For `mock` it contains `X-Api-Key: mock-secret-key`, the same value as the stripped `apiKey`, so
  the key the `apiKey` stripping is meant to hide is in the response anyway. In production the
  outbound header may hold a different secret, but it is a secret all the same. The route strips
  only the field named `apiKey`.
- The registry container itself serves the plain `apiKey` (`http://127.0.0.1:27080/platforms.json`).
  It must never be published outside the container network (`registry/README.md`). Locally it is
  bound to 127.0.0.1.

---

## `GET /admin/v1/authorities`

`GET /admin/v1/authorities` · admin JWT · `registry` `/authorities.json`

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Authority id, for example `auth-xroad-test` |
| `name` | string | Display name |
| `registryCode` | string | X-Road member code. The X-Road guard matches `X-Road-Client` against it. |
| `subsets` | array of strings | eFTI subsets the authority may request (`EU01` …). See [eFTI subsets](../../data/subsets.md). |

An authority has no `status`. It exists while its file exists. Nothing is removed.

```bash
curl -s -i http://127.0.0.1:27086/admin/v1/authorities -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 1137

[{"id":"auth-summary-eu03","name":"Summary test EU03","registryCode":"70000771","subsets":["EU03"]},{"id":"auth-summary-none","name":"Summary test none","registryCode":"70000772","subsets":[]},{"id":"auth-xroad-ambig-a","name":"X-Road Ambiguous Registry Code Fixture A","registryCode":"70000091","subsets":["EU01","EU02"]},…,{"id":"auth-xroad-tm-no-eu02","name":"X-Road Transport Means Authority Without EU02","registryCode":"70000092","subsets":["EU01"]}]
```

The local registry has ten authorities, most of them test fixtures.

```bash
curl -s "http://127.0.0.1:27086/admin/v1/authorities?limit=2&offset=7" -H "Authorization: Bearer $TOKEN"
```

```json
[{"id":"auth-xroad-test","name":"X-Road Test Authority","registryCode":"70000099","subsets":["EU01"]},{"id":"auth-xroad-tm","name":"X-Road Transport Means Authority","registryCode":"70000093","subsets":["EU01","EU02"]}]
```

Audit: `authority.list`.

## `GET /admin/v1/authorities/{id}`

`GET /admin/v1/authorities/{id}` · admin JWT · `registry` `/authorities/<id lower>.json`

```bash
curl -s -i http://127.0.0.1:27086/admin/v1/authorities/auth-xroad-tm -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 116

{"id":"auth-xroad-tm","name":"X-Road Transport Means Authority","registryCode":"70000093","subsets":["EU01","EU02"]}
```

```bash
curl -s -i http://127.0.0.1:27086/admin/v1/authorities/auth-none -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 404 Not Found
content-type: application/json
content-length: 21

{"error":"Not Found"}
```

Audit: `authority.read`.

## Notes

- `permissions-matrix.md` §3.3 still lists `POST`/`PUT`/`DELETE` on gates, platforms and
  authorities (under `/api/v1/…`) and `…/ping` routes. None exist. The registries are read-only
  (ADR-011) and the paths are `/admin/v1/…`.
- The audit row records the id that was asked for, never the returned content (it may carry
  certificate material).
- [Consignment summary](./consignments.md#get-adminv1consignment-summary) reads one authority's
  `subsets` from the same registry.
