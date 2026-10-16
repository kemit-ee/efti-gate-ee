# Turvis — integration contract (eFTI gate side)

**Turvis** is a Bürostack component focused on security-sensitive operations: hardened payload parsing, schema validation (XSD, JSON Schema, …), and cryptography (XMLDSig verify/sign, XML Encryption decrypt/encrypt). It is designed as a shared Bürostack component — any fleet service that needs these operations can call it over the same HTTP contract.

This directory holds the **Phase 1 slice of the Turvis contract that this gate's `edelivery` service consumes**: four cryptography endpoints. Payload-validation and hardened-parse endpoints are planned follow-ups under the same `/turvis/v1/<operation>/<mechanism>` URL shape; they are **not** part of what the dev team integrates here.

## What's in this directory

| File | Purpose |
|---|---|
| [`SPEC.md`](./SPEC.md) | **Authoritative integration contract.** Endpoint catalogue, JSON Schemas, reason-code enum, algorithm URIs, auth modes (interim shared-token + post-A3 mTLS), latency budget, observability + audit query API, edelivery-side integration contract, examples. |
| [`dsl-contracts/`](./dsl-contracts/) | **Authoritative REST contract source.** Four Ruuter DSL files that double as (a) the Phase 1 `turvis-mock` compose service and (b) the Phase 2 contract the real Turvis MUST honour. SPEC §4 JSON Schemas are companion documentation for details DSL `allowlist:` can't express. See `dsl-contracts/README.md` for the contract-change workflow. |

## Endpoints (Phase 1)

Four `POST` endpoints under `/turvis/v1/<operation>/<mechanism>`:

- `/turvis/v1/verifications/xmldsig` — verify inbound XMLDSig
- `/turvis/v1/signatures/xmldsig` — sign outbound SOAP envelope
- `/turvis/v1/encryptions/xmlenc` — WS-Encryption encrypt
- `/turvis/v1/decryptions/xmlenc` — WS-Encryption decrypt

Future (same URL shape; not in this contract): `/turvis/v1/validations/{xmlschema,jsonschema}`, `/turvis/v1/parsings/{xml,json,…}`.

## Scope boundary

- **Dev-team work on `kemit-ee` side**: #305 (edelivery integration) · #306 (turvis-mock DSL deployment) · #307 (perimeter trust, blocked on #309 A3) · #308 (cutover).
- **Turvis's own build**: tracked separately in the Turvis repo (external, TBD). How Turvis implements the crypto, stores/rotates keys, chooses its stack, sets retention — all Turvis's responsibility.

## Related

- `#304` — parent epic (A9 / Turvis integration)
- `#309` — A3 (mTLS mesh + NetworkPolicy); pins the post-A3 mesh identity Turvis presents, which `#307` consumes
