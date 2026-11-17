# Deployer

> **Goal:** a gate runs in a real environment with its own secrets, its own registry, its own AS4
> key and a first administrator, and none of the development values.

This page is the English route through the deployment inventory. The detailed source is
[`docs/specs/deploy/resource-and-secrets-requirements.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/specs/deploy/resource-and-secrets-requirements.md)
(Estonian, with every secret by name, consumer and gotcha) and
[`docs/specs/deploy/README.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/specs/deploy/README.md).
The Helm charts live in a separate repository (`services/efti/devops`), not in this one. Where the
chart state matters it is noted below.

> **Read first.** The local network and the compose files carry **development values that are
> public in the repository**. Nothing in a deployed environment may use them. See
> [Ports and credentials](../reference/ports-and-credentials.md) for the list of what to replace.

## Path

1. Practice the whole thing locally: [Getting started](../getting-started/prerequisites.md).
2. Decide the AS4 cryptography mode: [Decision maker](./decision-maker.md#production-readiness).
3. Provide the components and secrets (this page).
4. Insert the first administrator: [First administrator](./first-admin.md).
5. Exchange certificates with peer gates: [Peer gate](./peer-gate-operator.md).
6. Register your authorities and platforms: [Change the registry](./registry-changes.md).
7. Verify: [Operator](./operator.md) and the Newman checks against your gate
   ([Run the checks](../getting-started/newman.md)).

## What you run

| Component | Needed | Notes |
|---|---|---|
| `database` (PostgreSQL 18, DB `efti`) | yes | Live data. Stateful, needs a volume and backups |
| `archive-database` (PostgreSQL 18, DB `efti_archive`) | yes | Cold storage for superseded consignments. A **separate instance**, own volume and backup. Grows with the retention setting (default 2555 days) |
| `tim-database` (PostgreSQL, DB `tim`) | yes | TIM's own sessions. A different database from `efti`, with its own password and migrations |
| `liquibase` | yes, one-shot | Runs the migrations. A job or init container |
| `resql`, `ruuter`, `ruuter-xroad` | yes | `ruuter-xroad` only if authorities connect over X-Road |
| `edelivery`, `xml-mapper` | yes | Kotlin services: AS4 and XML mapping |
| `registry` + `registry-build` | yes | `registry-build` is an init container or one-shot. Without the registry every guard denies |
| `tim`, `tim-init` | yes | Identity. `tim-init` prepares the CA bundle |
| `ui` | yes | Static nginx |
| `cronmanager` | yes | Scheduler for the clean-up jobs; the gate never schedules its own |
| `ruuter-xroad-mock` | optional | The public developer mock. Skip it if you serve no outside developers |
| `turvis`, `turvis-trust` | optional, opt-in | Production AS4 cryptography. See below |
| `tara-mock`, mock platform | no | Development and test only |

CPU and memory are in the source document. They are a floor taken from the compose limits and
load tests, not a sizing: start from the "recommended production start" column there and measure.
Several services have no CPU limit in compose.

## Configuration that is not secret

Set per environment (full table in the source document):

| Variable | Meaning |
|---|---|
| `OWN_GATE_ID` | This gate's id. It must be the same in Ruuter's `constants.ini` and in the `edelivery` and `xml-mapper` environments, **and** equal the file name `registry/gates/<OWN_GATE_ID>.yml` **and** the AS4 keystore alias |
| `*_URL` | Service URLs (Kubernetes service names) |
| `REGISTRY_REFRESH_SECONDS` | How often `edelivery` re-reads the registry (default 10) |
| `DEV_LOGIN_ENABLED` | **Must be `false`.** It enables a login without TARA |
| `TURVIS_INTEGRATION_MODE` | `legacy` (default, in-process crypto), `shadow`, or `turvis` |
| TIM `public_base_url`, `allowed_redirect_uris` | Add the production domain |

## Secrets

Principles:

- **One source, one delivery.** Secrets live in the environment's secret store and reach containers
  through Kubernetes Secrets (for example ExternalSecret). Never in Helm values, git, CI variables or
  an image. **No image contains registry data, keys, certificates, tokens, passwords or keystores.**
- **Three delivery forms:** environment variables from a Secret, files from a Secret (Ruuter's
  `constants.ini`, the TIM JWT key, the AS4 keystore), and `platforms/` into `registry-build`.
- Some values must be identical in two places. Change both in one deployment, or the link breaks:

| Value | Where it appears |
|---|---|
| `efti` database password | `database` `POSTGRES_PASSWORD`, `RESQL_EFTI_PASSWORD`, `LIQUIBASE_COMMAND_PASSWORD` |
| `efti_archive` database password | `archive-database` `POSTGRES_PASSWORD`, `RESQL_ARCHIVE_PASSWORD` (a different value from the `efti` one) |
| `TIM_ADMIN_TOKEN` | `tim` environment and Ruuter's `constants.ini` |
| `INTERNAL_SERVICE_TOKEN` | Ruuter `constants.ini`, `constants-xroad.ini`, and the `edelivery` and `xml-mapper` environments |
| `ARCHIVE_OPS_TOKEN` | Ruuter `constants.ini` and CronManager's `OPS_AUTHORIZATION` (`Bearer <token>`) |
| `RESQL_INTERNAL_TOKEN` | `resql`, `edelivery`, and `RESQL_TOKEN` in both `constants*.ini` |
| AS4 certificate | `own.p12`, your own `registry/gates/<id>.yml` `eDeliveryCert`, and every partner's registry |

Notes that cost time when missed:

- **Ruuter reads `constants.ini` only at start.** Keep the whole file as a Secret and restart Ruuter
  after changing it. A `subPath` mount does not refresh in a running pod.
- **`AUDIT_SALT` is critical and must not be changed in a live environment.** It hashes personal
  codes in the audit log; changing it makes old and new hashes incomparable. Make sure it is set:
  without it the changelog keeps the literal `${AUDIT_SALT}` and hashes with that text.
- **TIM's JWT signing key must be a persistent Secret.** Without it a new volume creates a new key
  and everyone is logged out. Rotating it also logs everyone out.
- **TIM talks to the real TARA** in production: register the client with RIA, set
  `TIM_TARA_CLIENT_ID` and `TIM_TARA_CLIENT_SECRET`, and do not trust the mock's CA bundle
  (`SKIP_TARA_MOCK_CA=1`).
- **A token set to a development default is a defect.** `INTERNAL_SERVICE_TOKEN` holders have
  admin-level access to every internal authority route, and `ARCHIVE_OPS_TOKEN` holders can run the
  archive and purge jobs.
- **CronManager** needs its own admin token of at least 32 random characters, and today its chart
  state is open: see the source document before you plan its rollout.

## Two databases, one trap

`efti` is the gate's data (users, consignments, audit). `tim` is TIM's own. They are different
databases with different credentials. TIM migrates itself (`auto_migrate`), and not through
Liquibase. The `efti_archive` database is a third, reached only through ReSql's `archive` datasource.

## Liquibase context

Exactly one changeset has the context `dev`: the seed of development users, including a Super Admin
with a known personal code. The repository's `liquibase.properties` runs with `contexts: dev`. In
production set `LIQUIBASE_COMMAND_CONTEXTS` (or `--contexts=`) to a value that does **not** contain
`dev`, for example the empty string. Then insert the first administrator by hand:
[First administrator](./first-admin.md).

## Registry in production

The registry folder in the repository holds **development fixtures** (`EU-EE`, `EU-MOCK`, the
`mock` platform with the public `mock-secret-key`, and the `auth-*` test authorities). If you mount
that folder in production, those fixtures go live. Supply your own:

- `registry-build` as an init container with `gates/`, `authorities/` and `trust-anchors/` from a
  ConfigMap, and `platforms/` (plaintext API keys) from a Secret, writing to an `emptyDir` that
  nginx serves read-only.
- The `registry` service has **no published port and no ingress**. It serves plaintext platform
  keys.
- A Turvis pod, if used, gets only `gates/` and `trust-anchors/`.
- `registry-build` fails the start on any invalid file, and on an expired certificate, even for a
  `DISABLED` gate.

At the time of writing the Helm charts in the devops repository still need the registry init
containers (a recorded follow-up) and the `cronmanager`, `registry` and `turvis-trust` charts are not
all merged. Check the devops repository for the current state.

## AS4 key and certificate

`edelivery` does not start without `own.p12`.

1. Create a PKCS#12 with **one entry**: the private key and its certificate (RSA). The **alias must
   equal `OWN_GATE_ID`**. Key password equals store password (`KEYSTORE_PASSWORD`).
2. The certificate must be valid. A peer rejects an expired one.
3. Store the file (base64) and password in the secret store, mount the Secret at `$KEYSTORE_DIR`
   (default `/app/certs`) read-only, and set `KEYSTORE_PASSWORD`. Always set the password: the code
   falls back to `changeit`.
4. Put the public certificate in your own `registry/gates/<OWN_GATE_ID>.yml` as `eDeliveryCert`,
   and give it to every peer operator.
5. **Check:** `edelivery` logs `partyId: <OWN_GATE_ID>, KeyIdentifier/SKI: …, SerialNumber: …` at
   start. The serial must equal `openssl x509 -in own.crt -noout -serial` (hex there, decimal in
   the log). A restart loop without that line means: file missing, wrong password, or wrong alias.

Rotation is a coordinated switch with every peer: [Peer gate](./peer-gate-operator.md) and
[Create and exchange gate certificates](../getting-started/certificates.md).

## Network isolation

Network policy is part of the security model, not an extra.

| Surface | Rule |
|---|---|
| `/efti/api/v1/authority/**` on Ruuter (port 8086) | Protected only by `INTERNAL_SERVICE_TOKEN`. The ingress **must not** expose it |
| `/xroad/**` (`ruuter-xroad`, port 8087) | No public route. Only the X-Road Security Server may reach it, and the Security Server's path prefix `/xroad/provide` is forwarded to `ruuter-xroad:8087` **without rewriting**. Restrict by source address or NetworkPolicy. Only `GET /xroad/provide/openapi` is readable without `X-Road-Client` |
| `/ops/**` | Not proxied by the UI nginx. Block it at the ingress. Only CronManager may call it |
| `registry`, `resql` | Internal only, no published port |
| `/services/msh` | The public AS4 entry point for peer gates |

Design background: [Perimeter trust model](../architecture/security-and-compliance/perimeter-trust-model.md)
and [ADR-006](../architecture/decisions/006-xroad-identity-and-subsets.md).

## Production AS4 cryptography (Turvis)

By default edelivery signs, verifies, encrypts and decrypts itself (`legacy`), which is for
evaluation. Turvis is the separate service intended for production. It is **opt-in**:
`TURVIS_INTEGRATION_MODE=turvis` (or `shadow`) with `TURVIS_URL` and a dedicated
`TURVIS_INTERNAL_TOKEN` that must differ from `INTERNAL_SERVICE_TOKEN`. `edelivery` refuses to start
with `TURVIS_URL` pointing at the mock (`turvis-mock`) in a production-like environment. Details:
[Turvis](../api/internal/turvis.md), [Perimeter trust model](../architecture/security-and-compliance/perimeter-trust-model.md).
After a registry edit, reload Turvis's trust view (`kill -s HUP turvis` or a restart).

## Deployment checklist

1. Databases created: `efti`, `efti_archive`, `tim`, each with its own password, backups configured.
2. Secrets in the store, delivered by Secret; the identical-value pairs checked.
3. `DEV_LOGIN_ENABLED=false`; Liquibase context excludes `dev`; no `tara-mock`.
4. Production registry supplied (not the repository fixtures); `registry-build` exits 0.
5. AS4 keystore mounted; the serial check passes.
6. Ingress exposes only what is meant to be public; `/xroad`, `/ops`, `registry`, `resql` and the
   internal authority routes are not.
7. CronManager runs with its jobs loaded (its boot log shows the job group).
8. First administrator inserted and a TARA login verified.
9. Probes green on every service ([Operator](./operator.md)).
10. Peer certificates exchanged; one request each way succeeds.
11. Newman or the Postman collection run against the new gate.

## What this book does not cover

- **Backup and restore, disaster recovery and upgrade procedures** are not written down in this
  repository beyond the runbook [`manual-rollback`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/manual-rollback.md)
  and the topology in [`docs/specs/non-functional.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/specs/non-functional.md).
  Plan them with your platform team.
- **Helm charts** are in the devops repository.
