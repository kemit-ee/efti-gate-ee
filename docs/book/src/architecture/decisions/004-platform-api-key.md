# ADR-004: Platform authentication with an API key (`X-Api-Key`)

> **Amended by [ADR-011](011-registries-as-signed-config.md) §5 (08.10.2026):** the platform key is now sourced from `registry/platforms/<id>.yml` **as plaintext** (`apiKey`), because the Ruuter expression engine has no hashing function. The SHA-256 `api_key_hash` and the `X-Api-Key` hash comparison have been dropped; there is no key-issuing endpoint. `registry/` is therefore a **secrets store**, but since 10.11.2026 (ADR-011 addendum "registry data is mounted, not baked") the keys are in no image layer: `platforms/` is mounted from a Secret into the `registry-build` step, and the comparison stays plaintext. The protection is network isolation (the `registry` port is never public) plus access control on the Secret and the registry. (Until 10.11.2026 the directory was built into the `registry` image and the keys were in the image layers.)


**Decision (Rainer Türner, Sten Viljus, Anton Keks, 25.08.2026):**

## Decision

eFTI platforms authenticate to the gate with an **API key** sent in the HTTP
header `X-Api-Key`. Each platform gets one key.

The key is **never stored in plaintext** — only its SHA-256 hash is saved in the
database (`platforms.api_key_hash`). The key is shown in plaintext exactly once,
at the moment of generation.

## Schema

| Column | Type | Content |
|---|---|---|
| `api_key_hash` | `BYTEA` | `digest(<key>, 'sha256')` |
| `api_key_hint` | `TEXT` | the first 8 characters of the hash hex — an irreversible label for the UI |
| `api_key_generated_at` | `TIMESTAMPTZ` | when the current key was generated |

`platforms` is append-only — every change (`update_platform`, ping, soft-delete)
carries these columns forward into a new row. `get_platforms` / `get_platform_by_id`
return only `api_key_hint`, `api_key_generated_at` and `has_api_key`, not the
hash or the key.

## Guard (`DSL/Ruuter/platforms/POST/v1/consignments.guard`)

1. `X-Api-Key` header missing → **401**
2. `digest(key,'sha256')` finds no active (`status != 'DELETED'`) platform → **401**
3. finds >1 active platform (a registry error) → **403** `forbidden-multi-platform`
4. exactly one → request allowed

The hash is computed and compared on the SQL side (`get_platform_by_api_key`); the key
never reaches the log. Log redaction removes the `X-Api-Key` header.

## Generation

`POST /admin/v1/platforms/api-key/:id` (admin). Adds an append-only `platforms` row
with a new random 24-byte key, stores the hash + hint + `NOW()`, and returns it in
plaintext **once**:

```json
{ "id": "...", "apiKey": "9f8e…c1b2", "apiKeyHint": "3a1f9c02", "apiKeyGeneratedAt": "..." }
```

## User interface

The platform list has a **"Generate API key"** button. Pressing it creates a new
key, shown in a separate modal together with a **"Copy"** button. Once the modal
is closed the key can no longer be seen. The list shows only
`apiKeyGeneratedAt` and `apiKeyHint`.

## Rationale

- The platform interface is machine traffic without a user — TARA/JWT does not fit.
- mTLS would have required a Member-State X.509 cert for every platform and a
  reverse-proxy configuration; an API key is simpler to adopt in the development and
  pilot phase. mTLS remains a possible hardening later.
- Storing as a hash means a database leak does not expose the platforms' credentials.

## Applied changeset

`DSL/Liquibase/changelog/20260902-platform-api-key.sql`
