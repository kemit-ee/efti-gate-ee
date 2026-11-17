# ADR-011: Registries (gates, platforms, authorities) as signed configuration, taking effect with a release

**Decision (01.10.2026, extended 08.10.2026 and 10.11.2026 — Sten Viljus, Rainer Türner; implementation Anton Keks):**
The registry of gates, platforms and authorised authorities **is not** a runtime REST resource or a
database table. It is configuration in the repo (`registry/`), changed via a PR, converted
to JSON (originally **at build time**; since 10.11.2026 at start, see the addendum "registry data is mounted,
not baked") and served statically. **Signing (§2, §8) is still a requirement that has
not yet been implemented** — it is the main open point of this ADR.

> **Status: implemented, except for the signature.** The original draft (01.10.2026) described
> a loader + a signed manifest + a separate DB role; the implementation left the signature undone and
> dropped the intermediate database layer entirely. This text describes the **actual state** and keeps
> the signing requirement in force.
>
> **Numbering.** The progressive refinements of this decision were, for a while, separate files
> (ADR-014, ADR-015, ADR-016) and are now **consolidated into this ADR**; their files are deleted and
> the next new decision continues from **017**. Exception: the comments in **already-applied
> Liquibase migrations** (`20260902-platform-api-key.sql`, `20261008-registry-sync.sql`,
> `20261009-drop-registry-tables.sql`) still refer to the old numbers, because changing those files'
> bytes would break Liquibase's checksums and the rule is "an immutable, persistent master history"
> (see `AGENTS.md`). Those references are historical; this ADR is the current content of those
> decisions.

## The problem

The registry is a **legal/contractual artefact**: joining is an official event, not a runtime
mutation. An unauthorised change or a faulty input causes direct harm:

- a gate URL swap → a signed AS4 message goes to the attacker's endpoint (Chain-C);
- a faulty gate row → `edelivery` does not start (F-EDEL-BOOTFAIL-1);
- a faulty PEM → poisoning of the trust anchor (F-EDEL-19);
- an authority with empty `subsets` → bypassing the subset check (F-SQL-1);
- Class-2 errors on all CRUD paths (double encoding, type errors, length limits etc.).

If the registry is mutable via an admin REST with a single admin JWT, then anyone with the
corresponding permission can overwrite the registry. A change must take effect **with a release**,
which leaves a trace: git history, review, signature, deploy log.

## Decision

1. **Source.** The registry lives in the repo under `registry/`:
   - `registry/gates/<id>.yml`, `registry/platforms/<id>.yml`, `registry/authorities/<id>.yml`;
   - the file name = `id`; certificates as YAML literal blocks (`|`), so they are readable in review;
   - the directory is not under `DSL/`, because each first-level directory there is a Ruuter project
     and the files would be read as routes;
   - missing and `null`-valued optional keys are **left out of the generated JSON**; empty
     collections stay (`subsets: []` means "an authority with no permissions", `headers: {}`);
   - **a platform's `apiKey` is plaintext** (§5, amends ADR-004).

2. **Signature — A REQUIREMENT THAT HAS NOT YET BEEN IMPLEMENTED.** During the release a **manifest**
   is signed that contains file-level SHA-256 hashes of both the **sources** (`registry/**/*.yml`)
   and the **generated artefacts** (`*.json`), plus the git commit and a timestamp. The public key
   **must not travel with the signature** (see "How to implement the signature" below). Until this is
   done, the registry is protected only by PR review and the release process, not by cryptography.

3. **Delivery.** `scripts/registry-to-json.py` (PyYAML) is both the **converter and the validator**:
   it checks the mandatory keys, `id` vs file name, the `status` enums, `countryCode`, PEM shape,
   `subsets` codes, unknown keys and duplicate ids. Conversion happens **at image build time**
   (`docker/registry/Dockerfile`, two-stage: `python:3.13-alpine` + PyYAML → `nginx:stable-alpine`).
   A static **nginx** serves on port 8080: `/<type>.json` (the whole registry as an array),
   `/<type>/<id>.json` and `health.json`. There is no application code, no runtime mount; an
   environment-specific registry means an environment-specific image. **Fail-closed is at build time**:
   a faulty file does not let the image build. CI runs the same converter (without `--out`), so a
   faulty registry also fails without a build.
   **[Superseded on 10.11.2026 by the addendum "registry data is mounted, not baked": conversion at image
   build time, "no runtime mount", "environment-specific image" and "fail-closed at build time" no longer
   apply. The converter runs as the one-shot `registry-build` step on mounted sources and fail-closed is
   at start again; the CI check stays.]**

4. **There is no registry in the database.** The tables `gates`, `platforms`, `authorities`, their
   read-models (`rm_gates`/`rm_platforms`/`rm_authorities`) and the types
   `gate_status`/`authority_status` were **deleted** (`20261009-drop-registry-tables.sql`).
   `protect_registry_append()` now applies only to the `users` table. `read_model_pointer`,
   `read_model_generation_seq` and `rm_consignment_*` stay — they are the shipments' (ADR-012),
   not the registry's.

5. **A platform key is plaintext (amends ADR-004).** The Ruuter expression engine has **no hashing
   function at all** (checked against the running engine: no `crypto`, `crypto.subtle`,
   `TextEncoder` or `atob`), so the guard cannot compute SHA-256. `registry/platforms/<id>.yml`
   contains `apiKey` as plaintext and `platforms/.guard.yml` compares `X-Api-Key` against it directly,
   allowing only an `ONLINE` platform. Consequence: **`registry/` (until 10.11.2026 also the image built from it) is a
   secrets store** [since 10.11.2026 the image is not a secret carrier: `platforms/` is mounted from a
   Secret, see the addendum; the plaintext comparison stays] — the port is never exposed, admin responses strip `apiKey` and return a derived
   `hasApiKey`, and the guard strips `apiKey` from the `${platform}` given to the handler too. The
   signature (§2) makes **forging** the key detectable, but not **unreadable** — that is a separate
   problem.

6. **CRUD is removed.** Admin `POST`/`PUT`/`DELETE` on gates, platforms and authorities, together
   with the sub-routes (`ping`, `api-key`), and their ReSQL files are deleted. The `GET`s stay and
   the Admin GUI is a **read-only view** that reads the same source as the runtime. `users` and
   `consignments` keep their admin write paths.

7. **Deletion.** There is no tombstone: a file gone from the directory is a deleted record, queries
   get a 404 and the guards refuse. The id (and the authority's `registryCode`) is free again. The
   history is given by **git**, not a table. `authorities.status` disappears entirely — an authority
   is active because its file exists.

8. **Ruuter constants — A REQUIREMENT THAT HAS NOT YET BEEN IMPLEMENTED.** The constants
   (`constants.ini`) should have the manifest hash/version for **drift detection**, not as a data
   source: if every environment builds its own images, "the wrong registry is deployed" is a real
   operational risk and cheap to detect. (Ruuter itself cannot compute hashes — see §5 — so it can
   only **compare** the value reported by the registry service against the constant, not derive it
   itself.)

9. **User CRUD stays REST** (ADR-002): users join and leave regularly, this is not a contractual
   artefact.

10. **Turvis's trust view is derived from the registry (addendum 20.10.2026, #316).** `registry/`
    remains the sole source of truth; there is no separate, manually maintained peer/trust file.
    Turvis (A9, crypto out of `edelivery`) reads only a generated (since 10.11.2026: written by the
    `registry-build` step), allowlist-based
    `turvis-trust.json` and **has no access** to the `registry` service. Details: the next section.

## Addendum: Turvis's trust view (A9, #316)

Context: [the Turvis integration contract](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/specs/turvis/SPEC.md) §7.2–7.4 (PR #311, the decision confirmed
as merge condition 1 of #304). This section fixes the decision at the ADR level; the specification
describes the protocol and the fields. The generation implementation is #315.

### Why one source

If Turvis had its own manually maintained peer list, the gate certificate and status would be in two
places: a rotation or a `DISABLED` would be forgotten in one place and the trust decision would
silently diverge. Therefore:

- `registry/gates/*.yml` (§1) is the **only** human-editable source of peer identity, status and
  certificate. The optional trust anchors (`registry/trust-anchors/*.pem`) are also part of this tree.
- Turvis's configuration is a **derivative**, not a second source: `scripts/registry-to-json.py` (§3)
  emits, in addition to the registry JSON, `turvis-trust.json` from the same tree, in the same run,
  at the same git revision (originally the same build; see the 10.11.2026 addendum).

### Why Turvis does not read the `registry` service

The `registry` service also serves `platforms.json` **with plaintext `apiKey`s** (§5). Turvis needs
only the gate's identity, status and certificate. Therefore:

- the trust view is generated on an **allowlist** (not a denylist) basis: `party_id`, `status`,
  `verified_identity`, `cert_pem`, `cert_ski`, `cert_subject_dn`, `valid_until`, `trust_anchors[]`,
  `allowed_chain_anchors`. `apiKey`, `headers`, URLs, `tlsCert` and platforms cannot get into the
  view; a test checks this (#315);
- the artefact is packaged **separately** as a small static image (or a volume), not in the
  `apiKey`-carrying `registry` image;
- Turvis's egress to the `registry` service has **no route**; the #309 NetworkPolicy need not open it,
  and if the artefact is delivered via a volume, Turvis needs no network path at all.

### Relation to this ADR's earlier decisions

| Section | Effect |
|---|---|
| §1 sources | Unchanged: files, not tables. Only the optional `registry/trust-anchors/` is added (the converter rule below). |
| §3 build-time delivery | **Superseded on 10.11.2026 by the addendum "registry data is mounted, not baked"** (the converter runs as the `registry-build` step; "the build fails" below now means "`registry-build` exits non-zero"). Originally: extended with a second output (`turvis-trust.json`); the same converter is still the validator. The build fails if an `ONLINE` partner's existing `eDeliveryCert` does not parse, is expired or not yet valid (`notBefore` in the future), `verified_identity` is duplicated, `allowed_chain_anchors` refers to a non-existent alias, or the output contains a key outside the allowlist. `eDeliveryCert` **stays optional in the registry** (see below). |
| §4 no registry in the database | Unchanged; the trust view does not touch the database. |
| §5 plaintext `apiKey` | Unchanged; this is exactly why Turvis does not see the `registry` service. |
| §2 signature, §8 manifest hash | **Still unimplemented requirements.** These are Phase 2 integrity candidates, see below. |

### Changes in the converter (#315 scope)

- **`.pem` files.** Currently the build fails on any non-`.yml` file in a type directory (`README.md`,
  `AGENTS.md`). New rule: `.pem` is allowed **only** under `registry/trust-anchors/` (the file name
  minus `.pem` = the anchor's alias); elsewhere the ban stays, and the same applies to other
  extensions in that folder.
- **Tests.** `tests/registry/test_registry_to_json.py` gets tests: a `.pem` under trust-anchors
  passes; a `.pem` under `gates/` fails; a faulty PEM in an anchor file fails; an
  `allowed_chain_anchors` reference to a **non-existent alias** fails; a duplicate alias is not
  possible (the file name is unique).

### `eDeliveryCert` stays optional

The registry contract does not change: `eDeliveryCert` is still optional on a gate and a gate without
a certificate is left out of the `edelivery` AS4 party map (`registry/README.md`). The check applies
**only at view generation**:

- a **certificate-less** `ONLINE` gate is **left out of the Turvis view** — exactly as in the
  `edelivery` party map. Both sides refuse, so deny wins does not disappear: inbound is
  `SIGNER_UNTRUSTED`, outbound does not reach Turvis (the partner is not in the party map);
- an **existing but unparseable, expired or not-yet-valid** `eDeliveryCert` (on an `ONLINE` or
  `DISABLED` gate) **breaks the build**, because it points to a faulty input, not to a deliberately
  missing certificate;
- a `DISABLED` gate **with a certificate** stays in the view (the exact response detail, SPEC §7.2);
  a `DISABLED` gate without a certificate is left out for the same reason as above.

### Status model: deny wins

The registry has only `ONLINE` and `DISABLED`. `DISABLED` records stay in the Turvis view so the
response can name the status, rather than say "unknown". A partner is usable only if **both** views
(the `edelivery` registry view and the Turvis trust view) say `ONLINE`; neither side may extend the
other's decision. A row with differing statuses is a **generation mismatch**, resolved by deploying
the appropriate artefacts, not by trusting the more permissive side. Table: SPEC §7.4.

**What the system does while the artefacts differ (a deploy gap, a replica difference).** Decision:
**a temporary difference is accepted and bounded by a 5-minute alarm**; the protocol is not changed.
Reason: `edelivery` does not send its `generation` with the request and Turvis does not see the
`edelivery` registry, so Turvis cannot compare generations within a single request. Until then:

- each side decides by **its own** view and a partner is usable only if **both** say `ONLINE` (the
  deny wins table, SPEC §7.4); neither side extends the other's decision;
- Turvis replicas with a different `generation` respond per request against their own snapshot and
  return `X-Turvis-Trust-Generation`; `edelivery` logs it with `audit_ref`, so every decision is
  retroactively traceable to a specific generation;
- an alarm fires if the replicas', or Turvis's and `edelivery`'s, generations differ for longer than
  5 minutes; the short overlap that comes with a rolling deploy is expected.

**Residual risk.** During a certificate rotation, a confirmed partner's signature may fail
(`SIGNER_UNTRUSTED`) on a replica that still loaded the old certificate, or pass on a replica that has
not yet gone `DISABLED`. Mitigation: the new certificate is added in the same change and the old one
is removed only after the partner's transition (SPEC §7.4); `DISABLED` takes full effect after all
replicas are updated, not instantly.

### Pinning, trust chain and visibility

- **Default per-partner pinning**: an empty `allowed_chain_anchors` means the signer's certificate
  must equal the registry's `eDeliveryCert`. This is the Phase 1 default outcome.
- **The trust chain is a partner data field**, not a mode in code: `allowed_chain_anchors: [alias,…]`
  switches one partner to anchors, if the eFTI PKI management provides them; no code change is needed
  (SPEC §9.4).
- **The generation is visible**: `generation` is the SHA-256 of the canonical content; Turvis and
  `edelivery` export it as a metric and Turvis returns the `X-Turvis-Trust-Generation` header. An
  alarm if the replicas', or Turvis's and `edelivery`'s, generations differ for longer than 5
  minutes (SPEC §7.4).
- **Failure**: the previous successful snapshot stays in use; on first start without a snapshot
  Turvis is not ready and responds `TRUST_STORE_UNAVAILABLE` (SPEC §7.3).

### Consequences

- ~~A registry change now requires rebuilding and deploying **both** artefacts (the `registry` and the
  trust view); a `restart` applies nothing (continues §3).~~ **Superseded on 10.11.2026:** one
  `registry-build` run writes both artefacts; no image is built (see the next addendum).
- A certificate rotation is one commit: the new certificate is added in the same change, the old one
  removed after the partner's transition (SPEC §7.4).
- One hash of the same content gives both sides a comparable `generation`; a difference is an alarm,
  not an assumption.

### Phase 2 integrity — OPEN

Phase 1: Turvis recomputes `generation` on load and refuses on a file hash mismatch; the artefact is
bound to the deployment via the image digest. **This is not a cryptographic signature.**

Phase 2 (a detached signature over `generation`, verified against a configured anchor before loading)
is an **open** choice: (a) this ADR's §2 release-time signing (+ §8 manifest hash) or (b) an A3 signed
manifest. Neither is implemented in the repo; it is a requirement, not a current control. The decision
must be made before the trust view is claimed to be cryptographically protected (tracked as open
question no. 5 below).

## Addendum (10.11.2026): registry data is mounted, not baked

Decision: Rainer Türner, 10.11.2026. Reason: under §3 the plaintext platform `apiKey`s, the certificates and
(for Turvis) the configuration ended up in image layers and from there in the image registry, the SBOM and
trivy output, and every deployment with its own registry had to build its own image. The new rule for the
whole gate: **no image contains registry data, platform `apiKey`s, certificates, tokens, passwords or
keystores.** Images contain code and non-secret static configuration (`nginx.conf`, `ruuter.yaml`).

### What changes

- **A new image `registry-build`** (`docker/registry-build`, CODE ONLY: python + `scripts/registry-to-json.py`
  + `build.sh`). It runs as a one-shot: a Compose service that dependants wait for with
  `service_completed_successfully`, a Kubernetes init container. It reads the **mounted** sources
  (`/registry`: `gates/`, `platforms/`, `authorities/`, `trust-anchors/`; env `REGISTRY_SOURCE`), validates
  them with **the same converter**, and writes the registry documents to `REGISTRY_OUT` (default
  `/out/registry`, volume `registry-data`) and the Turvis trust view to `TRUST_OUT` (default `/out/trust`,
  volume `turvis-trust-data`) in **one run**. The registry `health.json` `trustViewGeneration` and the trust
  view `generation` are therefore equal automatically. Publishing replaces files by rename, `health.json`
  last. An empty `REGISTRY_OUT` means trust view only (a Turvis pod gets only `gates/` +
  `trust-anchors/`, never `platforms/`).
- **`registry` and `turvis-trust` are stock nginx + `nginx.conf` only.** They mount `registry-data` /
  `turvis-trust-data` read-only at `/usr/share/nginx/html`. URLs, port 8080, `Cache-Control: no-store`, the
  JSON 404 and `health.json` are unchanged.
- **`turvis` uses the Turvis release image unchanged** (`${TURVIS_IMAGE:-turvis:0.1.0-alpha}`, which no longer
  ships configuration, trust view or keys). Mounted read-only: `docker/turvis/turvis.yaml` →
  `/etc/turvis/turvis.yaml`, `turvis-trust-data` → `/etc/turvis/trust`, the PKCS#12 →
  `/run/secrets/turvis/own.p12`; `TURVIS_INTERNAL_TOKEN` and `TURVIS_KEY_PASSWORD` come from the environment.
  `docker/turvis/Dockerfile` and `local/registry.Dockerfile` were deleted.
- **A registry edit applies with `docker compose up -d registry-build`** (no rebuild; nginx serves the new files
  at once, `edelivery` picks them up within `REGISTRY_REFRESH_SECONDS`, Turvis reloads its trust view on
  `docker compose kill -s HUP turvis` or a restart). In `local/`:
  `docker compose --env-file gates/<g>.env up -d registry-build` (the local `registry-build` assembles the
  source from `registry/{authorities,platforms,trust-anchors}` + `local/network` as `gates/` + this gate's
  `mock-edelivery` platform derived from `network/<GATE_ID>.yml`, which `local/registry.Dockerfile` used to do).
- **Production / Kubernetes.** Registry pod: init container `registry-build` with `gates/`, `authorities/`,
  `trust-anchors/` from a ConfigMap and `platforms/` (plaintext `apiKey`s) from a Secret (ExternalSecret),
  writing to an `emptyDir` served by the nginx container. Turvis pod: init container `registry-build` with only
  `gates/` + `trust-anchors/` (`REGISTRY_OUT` empty) → `emptyDir` mounted at `/etc/turvis/trust`; `turvis.yaml`
  from a ConfigMap; `own.p12`, the token and the key password from Secrets. A deployment with its own registry
  **no longer needs its own image build**. **Required follow-up:** the devops charts
  (`services/efti/devops`, outside this repo) must be changed accordingly — `registry-build` image, init
  containers, ConfigMap/Secret.

### What it supersedes

| Section / text | Effect |
|---|---|
| §3 "conversion at image build time", "no runtime mount", "environment-specific registry = environment-specific image" | **Superseded.** The converter runs as the `registry-build` step on mounted sources; the image is the same in every environment. |
| §3 "fail-closed at build time" | **Superseded.** Validation happens (1) in CI (unchanged: the converter without `--out` in both CI jobs) and (2) **at every start** in `registry-build`. A bad file exits non-zero, names the file and field, **publishes nothing**, and the dependants (`registry`, `turvis-trust`, `turvis`, `ruuter`, `edelivery`) do not start — fail-closed is again at start, not at build. |
| Consequences: "Platform keys are in the image layers" | **Superseded.** `apiKey`s do not travel in image layers, the SBOM, trivy output or image storage; they live only in the Secret (and, in development, in the repo's `registry/platforms/`). The "image is never the secret carrier" rule holds again. |
| Consequences: "A registry change requires rebuilding the image", "`develop.watch` does not help" | **Superseded.** `docker compose up -d registry-build`; no image build. |
| Consequences: "There is no runtime validation" | **Superseded** (see fail-closed). |
| Turvis addendum: "rebuild both artefacts" | **Superseded.** One `registry-build` run writes both; `generation` matches automatically. |
| §5 plaintext `apiKey`, compared verbatim in the DSL | **Unchanged.** Ruuter still has no hash function; only the source of the key changed. |
| §1, §4, §6, §7, §9; §2 signature and §8 manifest hash | Unchanged; §2 and §8 are still unimplemented. |

### Consequences

- `registry` and `turvis-trust` port 8080 stays internal (`platforms.json` still carries plaintext keys); the
  `registry-data` volume is as sensitive as the Secret.
- By default Compose uses the repository's `./registry` (development fixtures, including `mock-secret-key`);
  a production deployment must supply `platforms/` from a Secret, not from the repository.
- The Phase 1 `generation` check and Phase 2 (signature) stay as in the Turvis addendum; an image digest no
  longer binds the trust view to the deployment — that binding has to come from an admission policy or the
  Secret/ConfigMap version.

## How to implement the signature

Four decisions, the first of which determines whether the signature is of any use at all:

1. **The trust anchor must be external to the artefact.** If the public key comes from the same
   artefact it protects, the check is self-proving and useless. Options, by strength: a cluster
   **admission policy** (a trusted CI identity, e.g. Kyverno/Gatekeeper + Sigstore OIDC), a
   **Kubernetes Secret / Vault `ExternalSecret`** (the repo's existing pattern), or **Sigstore
   keyless** (no key at all — trust is the Fulcio cert + the Rekor log).
2. **What to sign.** The **bytes** of the manifest, and the manifest carries the hashes:
   `{version, gitCommit, created, sources:{path: sha256}, artifacts:{path: sha256}}`. The check is
   then three independent steps: (a) the signature over the received manifest's bytes — this way no
   canonicalisation is needed and no PyYAML version is carried; (b) the sources' hashes are
   recomputed from the tree; (c) the converter is run and the hashes of its output are compared
   against `artifacts` — **without step (c) the converter itself is in the trust chain** and a
   modified `registry-to-json.py` would emit JSON differing from the signed sources. Use a standard
   envelope (DSSE / in-toto), not your own format; Ed25519, unless FIPS requires ECDSA-P256.
3. **Where the key lives.** The private key: an HSM / Cloud KMS (`gcpkms://`, `awskms://`, Vault
   Transit) or a hardware token (physical presence); **in a CI variable only if the threat is
   mistakes, not compromise** — a compromised pipeline signs everything. The public key: from a Secret
   or an admission policy, **not** from the image. Rotation: trust **a set of keys** (an allow-list of
   key IDs), not a single key.
4. **When to verify.** At build time (fast feedback, but requires external pinning), **at deploy time
   with an admission policy** (the only layer that catches "someone pushed a replacement image"), at
   container start with an init container (restores the start-time fail-closed that §3 gave up), and
   **consumer-side on load in `edelivery`** (the JVM has the crypto; Ruuter does not — see §5). The
   build may only **verify, not sign**.

## Rationale

- A registry change goes through a PR, review and deploy — this is a deliberate feature, not a
  limitation.
- One source of truth: the derived copy between git and the database disappears, along with its
  read-models, purges, tombstones and idempotency lifecycle.
- The readers are HTTP clients, so a change need not be migrated or rolled back.
- A faulty registry never reaches start-up (a build gate), rather than merely being logged.

## Consequences and costs

- **The registry is a critical dependency on the request path:** if `registry` does not respond →
  platform and X-Road requests do not authenticate and `edelivery` cannot get the party registry.
  `ruuter` and `edelivery` depend on the service's health.
- **Ruuter does more work per request** (a JSON document + a filter as a DSL expression, rather than
  an indexed SQL query). The registries are small, so it is acceptable.
- ~~**Platform keys are in the image layers** (and therefore in the image registry, the SBOM, trivy
  scans).~~ **Superseded on 10.11.2026:** the keys are only in the mounted Secret; no image contains
  them. Original text: this conflicted with the rule "container image is never the carrier"
  (`identity-and-access/README.md`) — a deliberate compromise that the §2 signature does not alleviate.
- ~~**A registry change requires rebuilding the image**; `develop.watch` does not help, because there
  is no mount.~~ **Superseded on 10.11.2026:** a change applies with `docker compose up -d registry-build`;
  no image is built.
- ~~**There is no runtime validation** — all checking is at build time.~~ **Superseded on 10.11.2026:**
  validation runs in CI and at every `registry-build` start.
- **`/<type>/<ID>.json` in uppercase gives a 404.** A static file server is case-sensitive, but ids
  used to be `CITEXT`. Therefore **the DSL lowercases the id before the query** (7 places), so that
  `EU-EE`, `eu-ee` and `Eu-Ee` behave the same; in the documents the `id` is still in its canonical
  form.
- **The database-side history disappears:** the trace of a registry change is git, not
  `created_at/revision` rows.

## Changes from the original draft

| Original draft (01.10.2026) | Actual (08.10.2026) |
|---|---|
| YAML + certificates as separate PEM files | YAML, certificates as literal blocks |
| Only a hash of the platform key | **Plaintext** `apiKey` (§5) |
| An init container loads the data into append-only tables | There are no tables; the converter at build time, nginx serves (10.11.2026: the converter runs as the `registry-build` init container on mounted sources, nginx serves) |
| The `app` role loses INSERT permission | Moot — there are no registry tables |
| A record gone from the directory → a tombstone | Moot — absence **is** deletion (§7) |
| Readers read the same tables | Readers read over HTTP (§8 change) |
| The manifest hash in the constants for drift detection | **Still undone** (§8) |
| The manifest is signed | **Still undone** (§2) — the main open point |

## Open questions

1. **The signing scheme and key storage** (§2) — the scheme, the key location and the trust anchor;
   see "How to implement the signature". A reference to the F-GC-3 design, which is not in this repo.
2. **Drift detection** (§8) — where the manifest hash lives in a constant and who compares it.
3. **API-key issuing and rotation** without a runtime endpoint: rotation = commit + build + deploy.
   Whether and when to move the keys out of the registry (into a Secret) so that rotation does not
   require a new release and so the "the image is not the secret carrier" rule is restored?
   **Partly resolved on 10.11.2026:** the keys are in a Secret and in no image; rotation = a Secret
   change + re-running `registry-build`, with no image build. The plaintext comparison (§5) stays.
4. **The hotfix-release process** — for a leaked key or a faulty record, the only path is a new
   signed release; a documented fast path is needed (e.g. a two-person rule).
5. **Phase 2 integrity of the Turvis trust view** — a release-time signature (§2/§8) or an A3 signed
   manifest; see "Addendum: Turvis's trust view". **Open**, unimplemented.

## Related

- [The Turvis integration contract](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/specs/turvis/SPEC.md) §7.2–7.4 (the trust view), kemit-ee/efti-gate-ee#304,
  #311, #315 (`turvis-trust.json` generation), #316 (this addendum), #309 (the network policy)
- [ADR-002](002-status-over-isactive.md) (user CRUD stays REST),
  [ADR-004](004-platform-api-key.md) (**amended by §5**: the key is plaintext, not SHA-256),
  [ADR-006](006-xroad-identity-and-subsets.md) (the authority's `subsets` is the source of authorisation),
  [ADR-012](012-read-model-vs-query-level-denormalisation.md) (shipment read-models — those stayed)
- kemit-ee/efti-gate-ee#187 (A7), kemit-ee/efti-gate-ee#190 (A7-IMPL)
