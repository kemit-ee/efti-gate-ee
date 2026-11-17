# Architecture: Platform Registry Management (Configuration-Driven, no database)

## Changes

- _Initial state. Change tracking begins at v1.0.0._
- **2026-10-08 — [ADR-011](../decisions/011-registries-as-signed-config.md) §3/§4/§5:** the registry left the Admin API and the database. It is declared in the git folder `registry/platforms/<id>.yml` and converted, with validation, into the JSON the `registry` service serves statically (§3; since 2026-11-10 converted by `registry-build` on mounted sources, so the keys are in no image; an edit is applied with `docker compose up -d registry-build`). The `platforms` table, its read model and the `api_key_hash`/`api_key_hint`/`api_key_generated_at` columns are **dropped** (§4), and the platform credential is a **plaintext `apiKey` in the file** (§5) — ADR-004's hash-only rule is dropped because Ruuter's expression engine has no hash function.

> Sub-architecture for the Platform Registry Management surface. For overarching rules see [theme README](index.md). AC are in [`../../cfr/registry-management/platform_registry.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/cfr/registry-management/platform_registry.md).

## Platform lifecycle at a glance

```mermaid
stateDiagram-v2
    [*] --> ONLINE: add registry/platforms/<id>.yml (status ONLINE)
    ONLINE --> ONLINE: commit a changed baseUrl / headers / certificate / apiKey
    ONLINE --> DISABLED: commit status DISABLED
    DISABLED --> ONLINE: commit status ONLINE
    ONLINE --> [*]: delete the file, then re-run registry-build
    note right of ONLINE
        Onboarded into eDelivery's party map
        only if an AS4 certificate is present
    end note
```

There is no `DELETED` state and no tombstone: absence is deletion.

## How a change reaches the consumers

1. A PR adds, edits or deletes a file under `registry/platforms/`.
2. `registry-build` is re-run (`docker compose up -d registry-build`, or a pod restart in Kubernetes): the
   mounted YAML is validated and converted to JSON and then served statically. A restart of `registry` alone
   changes nothing.
3. Ruuter reads `GET /platforms.json` (guards, list) or `GET /platforms/<id>.json` (details, dataset
   and follow-up forwarding) and filters in the DSL; `edelivery` reads `/platforms.json` into its AS4
   party map and refreshes it every `REGISTRY_REFRESH_SECONDS`.

A platform with no `eDeliveryCert` never reaches `edelivery`'s party map, which is why the REST-only
`mock` platform can authenticate without being a valid AS4 peer.

## Authentication credential

`apiKey` in the registry file is the platform's inbound `X-Api-Key`. `platforms/.guard.yml` reads
`/platforms.json`, keeps platforms where `apiKey` equals the header **and** `status === 'ONLINE'`,
and denies everything else — a null `apiKey` can never match, so an uncredentialed platform is
unreachable by construction.

Because this is a live secret (mounted, never baked into an image since the 2026-11-10 amendment):

- the `registry` service must never be exposed beyond the internal network (no published port in
  `compose.yml`, no ingress rule, not proxied by the UI). In production `platforms/` is a Secret mounted
  into `registry-build`; the keys therefore are not in image layers, image storage, the SBOM or the trivy
  scans, only in the Secret and the served `registry-data` volume / `emptyDir`. Local Compose mounts the
  repository's `./registry` dev fixtures, which a production deployment must replace;
- the admin read routes strip `apiKey` from every response and expose a derived `hasApiKey` boolean;
- the guard strips `apiKey` from the platform object it passes on to handlers through `${platform}`.

Rotation is a commit (production: a Secret update) plus a `registry-build` re-run; the new secret is handed to the platform
operator out of band. There is no runtime endpoint that mints or rotates keys any more.

## Rationale

Platform metadata (base URL, headers, certificates, credential) drives Platform-API authentication
and the forwarding target for dataset and follow-up requests. All of it is contractual: a wrong base
URL or a poisoned certificate is a routing or trust failure. Keeping it declarative removes the
second copy in the database and makes the credential visible in review — at the cost of the
credential being plaintext in the repository (production: in the Secret) and in the served documents,
which is the deliberate trade recorded in ADR-011 §5. Until 2026-11-10 it was also in the registry image
itself; the mount amendment removed that.
