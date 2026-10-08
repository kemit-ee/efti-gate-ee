# `registry/` — declarative gate / platform / authority registry

This folder is the **source of truth** for the three contractual registries the gate serves:
peer gates, eFTI platforms, and competent authorities. It replaces the admin-API CRUD that used
to own them. See [ADR-014](../docs/architecture/decisions/014-registry-as-git-folder.md) (which
implements [ADR-011](../docs/architecture/decisions/011-registries-as-signed-config.md)).

Editing a registry entry means **editing a file here, opening a PR, and deploying** — not clicking
in the Admin UI or calling a REST endpoint. The Admin UI reads these tables; it can no longer write
them, and neither can any HTTP route.

## Layout

```
registry/
  gates/<id>.json         e.g. EU-EE.json, EU-EE31.json
  platforms/<id>.json     e.g. mock.json
  authorities/<id>.json   e.g. auth-mta.json
```

- One JSON object per entity; the **file name (minus `.json`) must equal the `id` field**.
- `id` is case-insensitive in the database (`CITEXT`); write it in the registry's canonical form
  (`EU-EE`, lower-case for platforms and authorities).
- Files that do not end in `.json` are ignored (so `.gitkeep` is fine).
- The folder is mounted read-only into the `registry-sync` container at `/registry`.

## How it reaches the database

A one-shot compose service, `registry-sync`, runs on every `docker compose up`:

1. reads and validates every file,
2. POSTs each registry as a batch to ReSql (`/efti/sync_gates`, `/efti/sync_platforms`,
   `/efti/sync_authorities`),
3. refreshes the admin read models (`/efti/refresh_registry_lists`),
4. reads the three registries back and fails if the database does not match this folder.

It runs before `ruuter` and `edelivery`, and every consumer waits for it
(`depends_on: service_completed_successfully`). A malformed file or a database mismatch therefore
**stops the stack from starting** rather than booting with a half-applied registry.

Because it is a one-shot service, editing a file does not re-run it while the stack is already up.
To apply a change to a running stack:

```sh
docker compose up registry-sync     # re-runs the loader, then exits
```

## Semantics

The tables are append-only, and "latest row per id wins". The sync is declarative, so:

- **New file** → the entity is inserted.
- **Changed file** → a new revision is appended; the previous one stays as history.
- **Unchanged file** → nothing is written. Restarting the stack does not grow the tables.
- **File removed** → a `DELETED` tombstone is appended. The rows are kept for audit.
- **File re-added** → the entity comes back (a `DELETED` id is reactivated by this path only; the
  admin API could never do that, and it no longer exists).

`status` is owned entirely by this folder. There is no ping job and no health monitoring flipping
it at runtime: a gate or platform is `ONLINE` because the registry says so, or `DISABLED` because
an operator committed that.

`last_ping_at` is vestigial and stays `NULL` — nothing pings any more.

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
| `eDeliveryCert` | string\|null | no | Public AS4 PEM. A platform without one is invisible to `edelivery`. |
| `tlsCert` | string\|null | no | Public TLS PEM. |
| `status` | string | yes | `ONLINE` or `DISABLED`. |
| `apiKeyHash` | string\|null | no | **SHA-256 of the platform's `X-Api-Key`, 64 lower-case hex chars.** Never the key itself. |

`apiKeyHash` is the inbound credential the platform presents to the gate in `X-Api-Key`. Only the
hash is ever stored, here or in the database; the plaintext is not recoverable from it. To rotate a
key, pick a new secret, hash it (`printf %s "$secret" | sha256sum`), commit the new hash, and hand
the secret to the platform operator out of band. If `apiKeyHash` is omitted the platform has no
inbound credential and cannot authenticate.

## Authority — `registry/authorities/<id>.json`

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | e.g. `auth-mta`. Must equal the file name. |
| `name` | string | yes | Human-readable name. |
| `registryCode` | string | yes | Business-registry code used for access control; must be unique among `ACTIVE` authorities. |
| `subsets` | string[] | yes | eFTI subsets the authority may request, e.g. `["EU01","EU05"]`. An authority entitled to nothing is `[]`. |
| `status` | string | yes | `ACTIVE` or `DELETED`. |

## Certificates

Certificates are inline PEM strings in the JSON, so an entity is self-contained and its content is
covered by the same review as the rest of the file. Encode newlines as `\n` (standard JSON); the
loader and the database both treat the value as a single opaque string. The PEM is compared
byte-for-byte, so a stray blank line or a missing trailing newline is a real change and will append
a new revision.

## Environment-specific content

This folder is committed with the local development / CI fixtures (the `EU-EE` own gate and the
self-loopback `EU-MOCK` gate, the `mock` and `mock-edelivery` platforms). Deployments mount their
own registry — the same way `constants.ini` is a dev file mounted read-only at runtime. The gate
only ever sees what is mounted at `/registry`.

## Limits

- **An empty subdirectory is a declaration, not a mistake.** `registry/gates/` with no `.json` file
  means "there are no gates", so the loader tombstones every gate in the database. The loader fails
  hard if `REGISTRY_DIR` itself is missing, but it cannot tell a deliberately emptied directory from a
  typo'd mount that still resolves — so keep the three subdirectories present and non-empty in any
  deployment that has entities, and treat an empty directory as a change worth double-checking.
- The whole batch per registry type is one ReSql request, capped by `server.max_body_bytes` in
  [`resql.yaml`](../resql.yaml) (1 MiB). Hundreds of entities with inline certificates fit
  comfortably; if a registry ever approaches the cap, raise the limit rather than splitting the
  files.
- `id`, `status`, `apiKeyHash` and PEM values are validated by the loader before any request is
  sent, so a typo fails startup with a file-and-field error instead of a database error.
