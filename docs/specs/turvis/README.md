# Turvis — integration contract (eFTI gate side)

**Turvis** is a Bürostack component focused on security-sensitive operations: hardened payload parsing, schema validation (XSD, JSON Schema, …), and cryptography (XMLDSig verify/sign, XML Encryption decrypt/encrypt). It is designed as a shared Bürostack component — any fleet service that needs these operations can call it over the same HTTP contract.

This directory holds the **Phase 1 slice of the Turvis contract that this gate's `edelivery` service consumes**: four cryptography endpoints. Payload-validation and hardened-parse endpoints are planned follow-ups under the same `/turvis/v1/<operation>/<mechanism>` URL shape; they are **not** part of what the dev team integrates here.

## What's in this directory

| File | Purpose |
|---|---|
| [`SPEC.md`](./SPEC.md) | **Authoritative integration contract.** Endpoint catalogue, JSON Schemas, reason-code enum, algorithm URIs, auth modes (interim shared-token + post-A3 mTLS), latency budget, observability + audit query API, edelivery-side integration contract, examples. |
| [`dsl-contracts/README.md`](./dsl-contracts/README.md) | How the contract is implemented and checked: the four Ruuter DSL files live in [`DSL/Ruuter-turvis-mock/`](../../../DSL/Ruuter-turvis-mock/) and double as (a) the Phase 1 `turvis-mock` compose service and (b) the Phase 2 contract the real Turvis MUST honour. SPEC §4 JSON Schemas are companion documentation for details DSL `allowlist:` can't express. The README holds the run commands, mock behaviour and the contract-change workflow. |

## Endpoints (Phase 1)

Four `POST` endpoints under `/turvis/v1/<operation>/<mechanism>`:

- `/turvis/v1/verifications/xmldsig` — verify inbound XMLDSig
- `/turvis/v1/signatures/xmldsig` — sign outbound SOAP envelope
- `/turvis/v1/encryptions/xmlenc` — WS-Encryption encrypt
- `/turvis/v1/decryptions/xmlenc` — WS-Encryption decrypt

Future (same URL shape; not in this contract): `/turvis/v1/validations/{xmlschema,jsonschema}`, `/turvis/v1/parsings/{xml,json,…}`.

## Phase 1 operating mode (no real Turvis yet, no external peers)

Turvis itself doesn't exist yet. Until it does, this gate runs with `turvis-mock` (a Ruuter DSL compose service built from `DSL/Ruuter-turvis-mock/`, delivered under #306) as its operational Turvis. In this mode:

- Local gate-to-gate flows work (both ends talk to the same mock; pass-through signatures/encryption are self-consistent).
- Internal gate functionality (upload, admin, UI, DB, X-Road) is unaffected.
- **External AS4 traffic to real EU-member peers is deferred to Phase 2** — `turvis-mock`'s pass-through output will not survive peer signature verification against Domibus / Holodeck / phax4as4. Peer-interop readiness blocks on the real Turvis being deployed.
- Pre-A3 interim auth (SPEC §11.2) with `AUTH_MODE=shared-token` and the Turvis-dedicated `TURVIS_INTERNAL_TOKEN` (not the fleet's `INTERNAL_SERVICE_TOKEN`) covers the window; mTLS mesh identity is pinned post-A3.

Operational consequence: **#306 (turvis-mock deployment) is a hard dependency for #305 (edelivery integration) and #308 (cutover)**, not a parallel ticket. Without `turvis-mock` running, edelivery has no counterparty for its HTTP calls after the private-key deletions in SPEC §13.6.

## Scope boundary

- **Dev-team work on `kemit-ee` side**: #305 (edelivery integration) · #306 (turvis-mock DSL deployment) · #307 (perimeter trust, blocked on #309 A3) · #308 (cutover).
- **Turvis's own build**: tracked separately in the Turvis repo (external, TBD). How Turvis implements the crypto, stores/rotates keys, chooses its stack, sets retention — all Turvis's responsibility.

## Related

- `#304` — parent epic (A9 / Turvis integration)
- `#309` — A3 (mTLS mesh + NetworkPolicy); pins the post-A3 mesh identity Turvis presents, which `#307` consumes
