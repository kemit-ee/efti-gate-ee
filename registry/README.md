# `registry/` — declarative gate / platform / authority registry

This folder is the **source of truth** for the three contractual registries the gate serves: peer
gates, eFTI platforms, and competent authorities. It is also the **only** place they live — there are
no registry tables in the database. See [ADR-015](../docs/architecture/decisions/015-registry-as-file-server.md).

Editing a registry entry means **editing a file here, opening a PR, and deploying**. The Admin UI
reads this data; it cannot write it, and neither can any HTTP route.

> **`registry/` is a secret store.** Platform entries contain their live `apiKey` in plaintext
> (ADR-015 dropped ADR-004's hash-only rule, because Ruuter's expression engine has no hash
> function). In Kubernetes mount this from a **Secret**, not a ConfigMap, and never expose the
> `registry` service beyond the internal network — it has no published port, exactly like ReSQL.

## Layout

```
registry/
  gates/<id>.json         e.g. EU-EE.json
  platforms/<id>.json     e.g. mock.json
  authorities/<id>.json   e.g. auth-mta.json
```

- One JSON object per entity; the **file name (minus `.json`) must equal the `id` field**.
- Ids are matched **case-insensitively** when looked up by id.
- Files that do not end in `.json` are ignored.
- The folder is mounted read-only into the `registry` service at `/registry`.

## How it reaches the consumers

The `registry` service (`docker/registry/`) runs alongside the rest of the stack and:

1. reads and **validates** every file at startup,
2. holds the registries in memory,
3. serves them — `GET /<type>.json` (the whole registry as one array), `GET /<type>/<id>.json` (one
   entity, or 404) and `GET /health`,
4. **fails to start on a bad file**, and `ruuter` and `edelivery` wait for it
   (`depends_on: service_healthy`), so the stack does not come up on a broken registry.

It does no filtering, no auth and no lookups: the `status` checks, the platform `X-Api-Key`
comparison and the authority registry-code ambiguity rule all live in the Ruuter DSL, which owns the
gate's policy. The whole-registry documents exist because **Ruuter has no loops**, so a list or a
by-code lookup cannot be assembled from individual file fetches.

Consumers:

| Consumer | Reads | Uses |
|---|---|---|
| `admin/GET/v1/{gates,platforms,authorities}` | `/<type>.json`, `/<type>/<id>.json` | admin views (`platforms` strips `apiKey`, adds `hasApiKey`) |
| `admin/GET/v1/gates/own` | `/gates/<OWN_GATE_ID>.json` | this gate's own details |
| `admin/GET/v1/consignment-summary` | `/authorities/<id>.json` | the authority's `subsets` |
| `efti/…/dataset-local`, `…/follow-up-local` | `/platforms/<id>.json` | `baseUrl`, `headers`, `eDeliveryCert` |
| `efti/…/authority/search` | `/gates.json` | `ONLINE` peers for the cross-gate fan-out |
| `platforms/.guard.yml` | `/platforms.json` | `X-Api-Key` comparison |
| `xroad/.guard.yml`, `xroad/GET/v1/subsets` | `/authorities.json` | `registryCode` → `subsets` entitlement |
| `edelivery` (Kotlin `RegistryClient`) | `/gates.json`, `/platforms.json` | AS4 party map |

## Applying a change

Content is loaded **once at startup**, so:

```sh
docker compose restart registry     # applies edited registry files
```

With `docker compose watch` the override file restarts the service on any change under `registry/`.
A malformed file makes that restart exit non-zero: the already-running stack is unaffected (the
service is only a startup dependency), but every registry lookup fails from then on — guards deny
and admin reads return 502 — until the file is fixed and the service starts again.

## Semantics

- **Removing an entity = deleting its file.** There is no `DELETED` status and no tombstone: the
  entity simply stops existing, so lookups 404 and guards deny. Git history is the only record that
  it was ever there, and an id can be reused freely.
- **`status` is owned entirely by this folder.** Gates and platforms are `ONLINE` or `DISABLED`;
  there is no ping job and no health monitoring flipping them at runtime (`last_ping_at` is gone
  with the table).
- **Authorities have no `status` at all**: an authority exists because its file exists. Revoking one
  means deleting the file.

## Gate — `registry/gates/<id>.json`

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | Logical id, e.g. `EU-EE`. Must equal the file name. |
| `countryCode` | string | yes | ISO 3166-1 alpha-2, upper case (`^[A-Z]{2}$`). |
| `eDeliveryUrl` | string | yes | AS4 access point, e.g. `http://edelivery:8081/services/msh`. |
| `eDeliveryCert` | string\|null | no | Public PEM used to verify AS4 messages from this gate. |
| `tlsCert` | string\|null | no | Public TLS PEM for the gate's HTTPS endpoint. |
| `status` | string | yes | `ONLINE` or `DISABLED`. |

## Platform — `registry/platforms/<id>.json`

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | e.g. `mock`. Must equal the file name. |
| `baseUrl` | string | yes | The platform's REST base URL. |
| `headers` | object | no | Headers the gate sends **to** the platform (default `{}`). |
| `eDeliveryCert` | string\|null | no | Public AS4 PEM. A platform without one is invisible to `edelivery` and is served over REST only. |
| `tlsCert` | string\|null | no | Public TLS PEM. |
| `status` | string | yes | `ONLINE` or `DISABLED`. Only `ONLINE` platforms authenticate. |
| `apiKey` | string\|null | no | **The plaintext `X-Api-Key` the platform presents to the gate.** |

`apiKey` is compared against the inbound `X-Api-Key` header by `platforms/.guard.yml`. A platform
with no `apiKey` (or a null one) can never authenticate. To rotate a key, commit a new value and hand
it to the platform operator out of band — there is no runtime endpoint that mints keys.

Because the platform object carries this credential:

- the registry service must stay internal (no published port, no ingress rule, not proxied by the UI);
- the admin read routes **remove `apiKey`** from every response and expose a derived `hasApiKey`
  boolean instead;
- `platforms/.guard.yml` strips `apiKey` from the platform object it passes to handlers.

## Authority — `registry/authorities/<id>.json`

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | e.g. `auth-mta`. Must equal the file name. |
| `name` | string | yes | Human-readable name. |
| `registryCode` | string | yes | Business-registry code used for X-Road access control. **Not unique across files by design**: two files sharing a code are denied as ambiguous rather than resolved arbitrarily. |
| `subsets` | string[] | yes | eFTI subsets the authority may request, e.g. `["EU01","EU05"]`. An authority entitled to nothing is `[]`. |

## Certificates

Certificates are inline PEM strings in the JSON, so an entity is self-contained and its content
is covered by the same review as the rest of the file. Encode newlines as `\n` (standard JSON).

## Environment-specific content

This folder is committed with the local development / CI fixtures (the `EU-EE` own gate and the
self-loopback `EU-MOCK` gate, the `mock` and `mock-edelivery` platforms, and the `auth-*` authority
fixtures that `tests/` relies on). Deployments mount their own registry — the same way
`constants.ini` is a dev file mounted read-only at runtime.
