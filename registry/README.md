# `registry/` — declarative gate / platform / authority registry

This folder is the **source of truth** for the three contractual registries the gate serves: peer
gates, eFTI platforms, and competent authorities. It is also the **only** place they live — there are
no registry tables in the database, and no HTTP write path. [ADR-011](../docs/architecture/decisions/011-registries-as-signed-config.md)
is the single record of this change: §1 the sources, §3 how they are delivered, §5 the secret-store consequence.

Editing a registry entry means **editing a YAML file here, opening a PR, and building the `registry`
image**. The Admin UI reads this data; it cannot write it, and neither can any HTTP route.

> **`registry/` is a secret store.** Platform entries contain their live `apiKey` in plaintext
> (ADR-011 §5 dropped ADR-004's hash-only rule, because Ruuter's expression engine has no hash
> function). Per ADR-011 §3 there is **no runtime mount**: the folder is copied into the `registry`
> image at build time, so the keys travel in **image layers** (and therefore in the image registry,
> the SBOM and the trivy scans). Never publish or ingress the `registry` port — it is internal-only,
> exactly like ReSQL's.

> **Turvis trust view (#315).** Turvis (A9) never reads this folder through the `registry`
> service. Its trust configuration is a *generated*, allowlist-filtered view of `gates/*.yml` and
> `trust-anchors/*.pem` (`turvis-trust.json`: no `apiKey`, headers, `tlsCert` or URLs) built by the same
> converter from the same revision and shipped as the separate `turvis-trust` image
> (`docker/turvis-trust/Dockerfile`); `registry/` stays the only hand-edited source. See
> [ADR-011, "Täiendus: Turvise usaldusvaade"](../docs/architecture/decisions/011-registries-as-signed-config.md)
> and [Turvis SPEC §7.2–7.4](../docs/specs/turvis/SPEC.md) and "Turvis trust view" below. A registry edit
> needs **both** images rebuilt (`docker compose up --build registry turvis-trust`).

## Layout

```
registry/
  gates/<id>.yml         e.g. EU-EE.yml
  platforms/<id>.yml     e.g. mock.yml
  authorities/<id>.yml   e.g. auth-mta.yml
```

- One YAML mapping per entity; the **file name (minus `.yml`) must equal the `id` field**.
- Ids are still matched **case-insensitively** when looked up by id (see "Id case" below).
- Any other file in a type directory (`.yaml`, `.json`, …) **fails the build** rather than being
  silently skipped; dot-files are ignored.
- Every scalar is a **string** — quote values YAML would otherwise turn into numbers or booleans
  (`registryCode: '70000099'`, `countryCode: 'NO'`). The DSL compares them with `===`, so an
  unquoted number would silently never match; the converter rejects it.
- Certificates are YAML literal blocks (`|`), so they stay readable in review.

## How it reaches the consumers

There is **no registry application server** and **no runtime mount**. `scripts/registry-to-json.py`
(PyYAML) is the converter *and* the validator, and it runs at **image build time**:

1. `docker/registry/Dockerfile` stage 1 (`python:3.13-alpine` + PyYAML) runs
   `python3 scripts/registry-to-json.py --source registry --out /srv/registry`. It **validates** every
   file — required keys, string types, `id` vs file name, the `status` enum, `countryCode`, absolute
   http(s) URLs, PEM shape, `subsets` codes (no duplicates), `headers` as string→string, unknown keys,
   case-insensitive duplicate ids, and a platform `apiKey` shared by several files — and exits non-zero on the first bad one, which **fails the
   build**.
2. Stage 2 (`nginx:stable-alpine`) serves the generated files from `/usr/share/nginx/html` on port
   **8080** (`[#REGISTRY_URL]` is `http://registry:8080`).

Generated documents:

| Document | Content |
|---|---|
| `GET /<type>.json` | the whole registry as one array, ordered by id |
| `GET /<type>/<id>.json` | one entity (id lower-cased in the path), or a JSON `404` |
| `GET /health.json` | `{status, gates, platforms, authorities}` counts |

nginx adds `Cache-Control: no-store` (the documents carry live keys), disables directory listing
(`try_files $uri =404`) and returns a JSON 404 body. It does no filtering, no auth and no lookups: the
`status` checks, the platform `X-Api-Key` comparison and the authority registry-code ambiguity rule
all live in the Ruuter DSL, which owns the gate's policy. The whole-registry documents exist because
**Ruuter has no loops**, so a list or a by-code lookup cannot be assembled from individual file
fetches.

`health.json` and the container healthcheck keep `depends_on: registry: service_healthy` working for
`ruuter` and `edelivery`. Since the validation now happens at build time, a malformed registry is
caught *before* anything starts — there is no runtime check left at all. CI runs the same converter
with no `--out` (`.github/workflows/e2e.yml`, `.gitlab-ci.yml`), so a bad registry fails CI without a
Docker build.

Consumers:

| Consumer | Reads | Uses |
|---|---|---|
| `admin/GET/v1/{gates,platforms,authorities}` | `/<type>.json`, `/<type>/<id>.json` | admin views (`platforms` strips `apiKey`, adds `hasApiKey`) |
| `admin/GET/v1/gates/own` | `/gates/<OWN_GATE_ID>.json` | this gate's own details |
| `admin/GET/v1/consignment-summary` | `/authorities/<id>.json` | the authority's `subsets` |
| `efti/…/dataset-local`, `…/follow-up-local` | `/platforms/<id>.json` | `baseUrl`, `headers`, `eDeliveryCert` |
| `efti/…/authority/search` | `/gates.json` | `ONLINE` peers for the cross-gate fan-out |
| `platforms/.guard.yml` | `/platforms.json` | `X-Api-Key` comparison |
| `xroad/.guard.yml`, `xroad/GET/provide/v1/subsets` | `/authorities.json` | `registryCode` → `subsets` entitlement |
| `edelivery` (Kotlin `RegistryClient`) | `/gates.json`, `/platforms.json` | AS4 party map |

## Applying a change

The JSON is baked into the image, so there is nothing to reload at runtime and **no mount to sync**:

```sh
docker compose up --build registry     # rebuild the image with the edited registry
```

`docker compose restart registry` alone changes nothing, and the override file has no
`develop.watch` entry for it — `docker compose watch` cannot apply a registry edit any more. The
newly built image is what the rest of the deployment then serves.

## Id case

A static file server is case-sensitive, but ids were case-insensitive when they lived in a `CITEXT`
column. Per-entity files are therefore written with the **lower-cased** id
(`/gates/eu-ee.json`), and the DSL lower-cases the id before every by-id request (7 sites). Requests
with any case — `EU-EE`, `eu-ee`, `Eu-Ee` — still resolve. Only a *manual* fetch has to be careful:
`GET /gates/EU-EE.json` against the server directly is a 404. The `id` value inside the documents and
the arrays keeps its canonical casing.

## Semantics

- **Removing an entity = deleting its file.** There is no `DELETED` status and no tombstone: the
  entity simply stops existing, so lookups 404 and guards deny. Git history is the only record that
  it was ever there, and an id can be reused freely.
- **`status` is owned entirely by this folder.** Gates and platforms are `ONLINE` or `DISABLED`.
  `DISABLED` is a full stop everywhere: it is excluded from the cross-gate search fan-out, the
  platform `X-Api-Key` guard, dataset/follow-up forwarding (treated as not found) and the `edelivery`
  AS4 party map (no messages to or from it);
  there is no ping job and no health monitoring flipping them at runtime (`last_ping_at` is gone
  with the table).
- **Authorities have no `status` at all**: an authority exists because its file exists. Revoking one
  means deleting the file.

## Gate — `registry/gates/<id>.yml`

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | Logical id, e.g. `EU-EE`. Must equal the file name and be ASCII (`A-Za-z0-9`, `.`, `_`, `-`; first character alphanumeric; max 64), because ids are compared case-insensitively and a look-alike such as a dotless `ı` must not fold onto a real id. |
| `countryCode` | string | yes | ISO 3166-1 alpha-2, upper case (`^[A-Z]{2}$`). |
| `eDeliveryUrl` | string | yes | AS4 access point, e.g. `http://edelivery:8081/services/msh`. |
| `eDeliveryCert` | string | no | Public PEM used to verify AS4 messages from this gate. A gate without one is left out of the `edelivery` party map (logged as a warning) **and of the Turvis trust view**. An existing one that does not parse, has expired or is not valid yet fails the build. |
| `tlsCert` | string | no | Public TLS PEM for the gate's HTTPS endpoint. |
| `allowedChainAnchors` | string[] | no | Aliases of `trust-anchors/<alias>.pem` this gate's certificate must chain to (Turvis chain-of-trust mode, SPEC §9.4). Absent or `[]` = **pinning**: the signer certificate must equal `eDeliveryCert`. An alias with no file fails the build. Only the Turvis view uses it; it is not part of the registry documents. |
| `status` | string | yes | `ONLINE` or `DISABLED`. |

## Platform — `registry/platforms/<id>.yml`

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | e.g. `mock`. Must equal the file name. |
| `baseUrl` | string | yes | The platform's REST base URL. |
| `headers` | object | no | Headers the gate sends **to** the platform. `headers: {}` is kept as an empty object. |
| `eDeliveryCert` | string | no | Public AS4 PEM. A platform without one is invisible to `edelivery` and is served over REST only. |
| `tlsCert` | string | no | Public TLS PEM. |
| `status` | string | yes | `ONLINE` or `DISABLED`. Only `ONLINE` platforms authenticate. |
| `apiKey` | string | no | **The plaintext `X-Api-Key` the platform presents to the gate.** Must be unique across platforms (a shared key would be answered 403 for both, so the build rejects it). |

`apiKey` is compared against the inbound `X-Api-Key` header by `platforms/.guard.yml`. A platform
with no `apiKey` can never authenticate. To rotate a key, commit a new value, rebuild the image and
hand the key to the platform operator out of band — there is no runtime endpoint that mints keys.

Because the platform object carries this credential:

- the `registry` image must stay internal (no published port, no ingress rule, not proxied by the UI);
- the admin read routes **remove `apiKey`** from every response and expose a derived `hasApiKey`
  boolean instead;
- `platforms/.guard.yml` strips `apiKey` from the platform object it passes to handlers.

## Authority — `registry/authorities/<id>.yml`

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | e.g. `auth-mta`. Must equal the file name. |
| `name` | string | yes | Human-readable name. |
| `registryCode` | string | yes | Business-registry code used for X-Road access control. **Not unique across files by design**: two files sharing a code are denied as ambiguous rather than resolved arbitrarily. |
| `subsets` | string[] | yes | eFTI subsets the authority may request, e.g. `["EU01","EU05"]`. An authority entitled to nothing is `[]` — an empty list is meaningful and is kept in the generated JSON. |

## Optional keys

The converter **omits absent and `null` optional keys** instead of writing `null`s into the JSON, so
a consumer never sees a key that the source did not declare. Empty collections are *not* absent:
`subsets: []` and `headers: {}` survive conversion, because "entitled to nothing" is a statement the
gate must be able to read.

## Certificates

Certificates are inline PEM in YAML literal blocks (`|`), so an entity is self-contained, its content
is covered by the same review as the rest of the file, and newlines need no escaping.

## Turvis trust view

`scripts/registry-to-json.py` also builds `turvis-trust.json` on **every** run (also when it only
validates), so a registry the view cannot represent fails CI and both image builds.

- **Trust anchors:** optional `registry/trust-anchors/<alias>.pem` (public CA certificates; the file name
  minus `.pem` is the alias, case-insensitively unique). This is the **only** directory where `.pem`
  files are allowed; any other file there, or a `.pem` in `gates/`, `platforms/` or `authorities/`,
  fails the build. A trust anchor that does not parse, has expired, is not valid yet or is not a CA certificate
  (`BasicConstraints CA:TRUE`) fails the build.
- **Allowlist:** each party carries `party_id` (the gate `id`), `status`, `verified_identity` (= the gate
  `id`), `cert_pem` (= `eDeliveryCert`), `cert_ski`, `cert_subject_dn`, `valid_until` and
  `allowed_chain_anchors`. Platforms, `apiKey`, `headers`, `tlsCert` and URLs have no code path into the
  view, and the finished document is re-checked against the allowlist.
- **Parties:** `ONLINE` and `DISABLED` gates with a certificate; gates without one are left out.
  `verified_identity` duplicates fail the build.
- **A certificate that expires or is not yet valid blocks both image builds** (the `registry` image runs
  the same converter), also for a `DISABLED` gate, and also when only an unrelated change is being
  deployed. Fix it by replacing the certificate or deleting the gate file; rebuilding an older revision
  after a certificate expired fails the same way. This is the fail-closed build of ADR-011 §3, applied to
  certificate validity.
- **`generation`:** SHA-256 of the canonical `parties` + `trust_anchors`. It does not include the
  revision, so equal content gives an equal `generation`. Certificates are re-encoded as PEM, so line
  wrapping or a trailing newline in the YAML does not change it. `source_revision` is informational and comes
  from the `SOURCE_REVISION` build argument (`--source-revision`, default `unknown`); pass the **same**
  value to the `registry` and `turvis-trust` builds. The `registry` image's `health.json` carries the same
  value as `trustViewGeneration`. GitHub Actions passes `git:<sha>` (compose interpolates
  `SOURCE_REVISION`); the GitLab `builds:` entries pass **no** build argument yet, so images from that
  pipeline report `unknown` until the platform component supports build args.
- **Shared certificates:** two gates with the same certificate cannot be told apart under pinning. The
  converter prints a `WARNING` (not a failure — the dev fixture `EU-EE`/`EU-MOCK` does this, and the SPEC
  lists no such build failure); a production registry must not trigger it.
- **Dev fixture:** `EU-EE` and `EU-MOCK` share one certificate (CN=EU-EE, valid until 2027-08-25), so the
  committed registry builds until that date; replace it before then. SPEC §13.11 removes `EU-MOCK` at cutover.

## Environment-specific content

This folder is committed with the local development / CI fixtures (the `EU-EE` own gate and the
self-loopback `EU-MOCK` gate, the `mock` and `mock-edelivery` platforms, and the `auth-*` authority
fixtures that `tests/` relies on). **An image built from this repository therefore serves the dev
registry, including the publicly known `mock-secret-key`.** Every real deployment must replace this
folder with its own `registry/` *before* building the image; there is no mount and no Secret that can
override the content of an already-built image.
