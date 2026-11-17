# Registry documents

The registry is the gate's list of peer gates, platforms and competent authorities (ADR-011). It
is a set of static JSON files, generated from `registry/**/*.yml` by the one-shot `registry-build` (at every start, from
mounted sources) and served by nginx. No image contains the data. There is no write path and no database table.

`GET /<document>` · on registry (G1 :27080, G2 :28080, inside `registry:8080` = `[#REGISTRY_URL]`) · no auth · source `registry/`, `scripts/registry-to-json.py`, `docker/registry-build/`, `docker/registry/`

## How it is produced

1. `registry-build` (`docker/registry-build`, `python:3.13-alpine`, PyYAML 6.0.2, cryptography
   50.0.1; code only) reads the **mounted** source `/registry` (env `REGISTRY_SOURCE`) and runs
   `registry-to-json.py --source … --out … --trust-out … --source-revision "$SOURCE_REVISION"` into a work
   directory, then publishes the result to `REGISTRY_OUT` (default `/out/registry`, volume `registry-data`) and
   `TRUST_OUT` (default `/out/trust`, volume `turvis-trust-data`) by rename, `health.json` last.
2. The script validates every file (required keys, all scalars strings, `id` = file name, `status`
   enum, `countryCode`, absolute URLs, PEM shape, certificate validity, subset codes, unknown keys,
   case-insensitive duplicate ids, shared `apiKey`) and exits non-zero on the first error: nothing is published and `registry`,
   `turvis-trust` and `turvis` do not start (read the `registry-build` container log). It
   also builds the Turvis trust view, so a registry the view cannot represent fails too.
3. `registry` (stock `nginx:stable-alpine` + `docker/registry/nginx.conf`, no data in the image) mounts
   `registry-data` read-only at `/usr/share/nginx/html` and serves it on port 8080.

Each gate of the local network runs its own `registry-build` (`local/compose.yaml`) over the
repository's `authorities/`, `platforms/` and `trust-anchors/`, with `gates/` replaced by
`local/network/` (one file per gate) and `mock-edelivery` derived from the gate's own entry. So each
gate's registry holds every other gate's current certificate. A registry change applies with
`docker compose --env-file gates/<gate>.env up -d registry-build` (no image rebuild);
`docker compose restart registry` changes nothing.

## Documents

| Path | Content |
|---|---|
| `/gates.json` | all gates, one array, ordered by id |
| `/gates/<id lower-case>.json` | one gate |
| `/platforms.json` | all platforms |
| `/platforms/<id lower-case>.json` | one platform |
| `/authorities.json` | all authorities |
| `/authorities/<id lower-case>.json` | one authority |
| `/health.json` | `{status, gates, platforms, authorities, trustViewGeneration}` |

The served folder of the local G1 registry (`docker exec efti-g1-registry find /usr/share/nginx/html -type f`):

```text
/usr/share/nginx/html/50x.html
/usr/share/nginx/html/authorities/auth-summary-eu03.json
… (10 authority files)
/usr/share/nginx/html/authorities.json
/usr/share/nginx/html/gates/g1.json
/usr/share/nginx/html/gates/g2.json
/usr/share/nginx/html/gates.json
/usr/share/nginx/html/health.json
/usr/share/nginx/html/index.html
/usr/share/nginx/html/platforms.json
/usr/share/nginx/html/platforms/mock-edelivery.json
/usr/share/nginx/html/platforms/mock.json
```

`turvis-trust.json` is **not** served here. It is in the separate `turvis-trust-data` volume, served by `turvis-trust` (below).

nginx adds `Cache-Control: no-store` to every answer, has `autoindex off`, and answers any unknown
path with `404 {"error":"not found"}`. The per-entity paths are lower-case; the `id` inside keeps
its case.

## Fields

Absent optional keys are left out (never `null`). Empty collections stay (`subsets: []`,
`headers: {}`).

### Gate

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | e.g. `G1`; ASCII, ≤ 64 characters |
| `countryCode` | yes | ISO 3166-1 alpha-2 |
| `eDeliveryUrl` | yes | AS4 access point of the gate |
| `status` | yes | `ONLINE` or `DISABLED`. `DISABLED` is excluded from the search fan-out and the AS4 party map |
| `eDeliveryCert` | no | PEM used to verify the gate's AS4 messages. Without it the gate is left out of the party map and the trust view |
| `tlsCert` | no | PEM of the gate's HTTPS endpoint |

`allowedChainAnchors` in the YAML goes only into the Turvis trust view, not into these documents.

### Platform

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | e.g. `mock` |
| `baseUrl` | yes | REST base URL the gate calls |
| `status` | yes | `ONLINE` or `DISABLED`; only `ONLINE` platforms authenticate and receive calls |
| `headers` | no | headers the gate sends **to** the platform |
| `eDeliveryCert` | no | AS4 PEM; with it the platform is reached over eDelivery, without it over REST |
| `tlsCert` | no | TLS PEM |
| `apiKey` | no | **the plaintext `X-Api-Key` the platform sends to the gate** |

### Authority

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | e.g. `auth-xroad-tm` |
| `name` | yes | display name |
| `registryCode` | yes | business-registry code, matched against the X-Road `memberCode`. Not unique by design: two files with one code are denied as ambiguous |
| `subsets` | yes | eFTI subsets the authority may request; `[]` = none |

## What is served, and the apiKey

The registry serves **everything** in the YAML, including the platforms' `apiKey`. nginx does no
filtering and no auth. The consequences (ADR-011 §5):

- the registry port must never be published or proxied (`compose.yml` publishes none; the UI
  nginx does not proxy it);
- the admin routes strip `apiKey` and add `hasApiKey` ([Gates, platforms, authorities](../admin/registries.md));
- `platforms/.guard.yml` compares `X-Api-Key` with `apiKey` and strips it before handing the
  platform object to a route.

Consumers:

| Consumer | Reads |
|---|---|
| `platforms/.guard.yml` | `/platforms.json` |
| `efti/…/dataset-local`, `…/follow-up-local` | `/platforms/<id>.json` |
| `efti/…/authority/search` | `/gates.json` (ONLINE peers) |
| `xroad/.guard.yml`, `xroad/GET/provide/v1/subsets` | `/authorities.json` |
| `admin/GET/v1/{gates,platforms,authorities}`, `gates/own`, `consignment-summary` | the lists and single documents |
| edelivery (`RegistryClient`) | `/gates.json`, `/platforms.json` (AS4 party map) |

## Examples

Certificates are shortened to `MIIC…`.

### Gates

```bash
curl -s -i http://127.0.0.1:27080/gates.json
```

```http
HTTP/1.1 200 OK
Server: nginx/1.30.6
Content-Type: application/json
Content-Length: 2545
Cache-Control: no-store

[
  {
    "id": "G1",
    "countryCode": "XA",
    "eDeliveryUrl": "http://g1-edelivery:8081/services/msh",
    "status": "ONLINE",
    "eDeliveryCert": "-----BEGIN CERTIFICATE-----\nMIIC…"
  },
  {
    "id": "G2",
    "countryCode": "XB",
    "eDeliveryUrl": "http://g2-edelivery:8081/services/msh",
    "status": "ONLINE",
    "eDeliveryCert": "-----BEGIN CERTIFICATE-----\nMIIC…"
  }
]
```

`/gates/g2.json` is the second object alone.

### Platforms

```bash
curl -s http://127.0.0.1:27080/platforms.json
```

```json
[
  {
    "id": "mock",
    "baseUrl": "http://ruuter:8086/mock-platform",
    "status": "ONLINE",
    "headers": {
      "X-Api-Key": "mock-secret-key"
    },
    "apiKey": "mock-secret-key"
  },
  {
    "id": "mock-edelivery",
    "baseUrl": "http://g1-edelivery:8081/services/msh",
    "status": "ONLINE",
    "headers": {},
    "eDeliveryCert": "-----BEGIN CERTIFICATE-----\nMIIC…"
  }
]
```

`mock` is a REST platform (the gate's own [mock platform](../platforms/mock-platform.md)); its
`headers` carry the key the gate sends to it, and `apiKey` the key it sends to the gate.

### One authority

```bash
curl -s http://127.0.0.1:27080/authorities/auth-xroad-tm.json
```

```json
{
  "id": "auth-xroad-tm",
  "name": "X-Road Transport Means Authority",
  "registryCode": "70000093",
  "subsets": [
    "EU01",
    "EU02"
  ]
}
```

`/authorities.json` holds the ten test authorities, among them `auth-xroad-test` (`70000099`), the
two ambiguous fixtures `auth-xroad-ambig-a`/`-b` (both `70000091`) and `auth-summary-none`
(`subsets: []`).

### Wrong case, and a file that is not there

```bash
curl -s -i http://127.0.0.1:27080/gates/G1.json
curl -s -i http://127.0.0.1:27080/turvis-trust.json
curl -s -i http://127.0.0.1:27080/gates/
```

All three:

```http
HTTP/1.1 404 Not Found
Content-Type: application/json
Content-Length: 21
Cache-Control: no-store

{"error":"not found"}
```

## The Turvis trust view

`registry-to-json.py --trust-out <dir>` writes `turvis-trust.json` and a `health.json` for
[Turvis](./turvis.md). The view is built from `gates/` and `trust-anchors/` only (a Turvis pod's
`registry-build` is not even given `platforms/`), so platforms, `apiKey`, `headers`, `tlsCert` and URLs
cannot reach the view; the script re-checks the result against an allowlist. The `turvis-trust` image is stock
nginx; it and the real Turvis (at `/etc/turvis/trust`) mount the volume.

| Field | Meaning |
|---|---|
| `schema_version`, `generation` (sha256), `source_revision` | document metadata |
| `trust_anchors[]` | `{alias, cert_pem}` from `registry/trust-anchors/<alias>.pem` (CA certificates) |
| `parties[]` | one per gate with an `eDeliveryCert`: `party_id`, `status`, `verified_identity` (= gate id), `cert_pem`, `cert_ski`, `cert_subject_dn`, `valid_until`, `allowed_chain_anchors` |

The `turvis-trust` service is not part of the local two-gate stack. The output below is from an older
image of this machine (when the view was still baked into the image) (`efti_gate_ee-turvis-trust:latest`, built from the committed `registry/`,
gates `EU-EE` and `EU-MOCK`), read with `docker run --rm --entrypoint sh … -c 'cat …'`:

```json
{
  "schema_version": "1.0",
  "generation": "sha256:617fe8d4741bb547a60509455368f32d01f0d7d0810bc0be1f7391c21e6d1404",
  "source_revision": "unknown",
  "trust_anchors": [],
  "parties": [
    {
      "party_id": "G1",
      "status": "ONLINE",
      "verified_identity": "G1",
      "cert_pem": "-----BEGIN CERTIFICATE-----\nMIID…\n",
      "cert_ski": "7c1da725d85d0a2a8203ee0adb4b34bb03157397",
      "cert_subject_dn": "CN=G1",
      "valid_until": "2027-08-25T14:32:17Z",
      "allowed_chain_anchors": []
    },
    {
      "party_id": "EU-MOCK",
      …
    }
  ]
}
```

Its `health.json`:

```json
{
  "status": "ok",
  "generation": "sha256:617fe8d4741bb547a60509455368f32d01f0d7d0810bc0be1f7391c21e6d1404",
  "parties": 2,
  "trustAnchors": 0
}
```

The registry's own `health.json` carries the `trustViewGeneration` of the view written in the same
`registry-build` run, so the two always match.

## Notes

- In the committed `registry/`, gate `EU-MOCK` uses the same certificate as `EU-EE` (same
  `cert_ski`, subject `CN=G1`). The converter only prints a `WARNING: parties … share one
  certificate` for this (`shared_certificate_warnings`); it is not a `registry-build` failure.
- Running the converter on the host failed here with `AttributeError: … has no attribute
  'not_valid_before_utc'`: the host has `cryptography` 41.0.7, the script needs 42 or newer. The
  image pins 50.0.1.
- The registry nginx has no `/health` route besides `/health.json`; an unknown path is a JSON 404.
- Field rules in full: `registry/README.md`.
