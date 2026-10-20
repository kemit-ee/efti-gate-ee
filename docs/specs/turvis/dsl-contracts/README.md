# Turvis: Ruuter DSL contract (Phase 1 mock, Phase 2 contract source of truth)

> Turvis is the Bürostack component for security-sensitive operations (crypto, schema validation, hardened parsing). This directory covers the **Phase 1 slice** that the eFTI gate's edelivery service integrates against: the four cryptography endpoints. Future validation / parsing endpoints will land as sibling directories (`validations/`, `parsings/`) under the same nesting pattern.

## Decision

Turvis's REST contract is **defined by Ruuter DSL files in this directory**, not by a separate OpenAPI spec. The same DSL files serve two purposes:

1. **Phase 1 (now)**: deployed as the turvis-mock compose service to unblock edelivery integration (A9-SUB-1 / kemit-ee#305) today, while the real Turvis is still being built.
2. **Phase 2 (later)**: the authoritative request/response contract that the real Turvis MUST implement. Whoever builds the real Turvis in Rust/Kotlin reads these DSL files to know exactly what shape to emit and accept.

**SPEC.md's §4 JSON Schemas remain as companion documentation** for details Ruuter DSL `allowlist:` can't express (base64 format, oneOf accept/reject, nested object validation, enum constraints). CI should enforce that any field documented in SPEC §4 is present in the corresponding DSL file's allowlist.

## File layout (eventual location in the gate repo)

Each Turvis operation is nested under `/<operation>/<mechanism>` so sibling mechanisms (JWS/JWE per RFC 7515/7516, COSE per RFC 8152) can be added later without colliding on request/response shape or reason-code enum:

```
DSL/Ruuter-turvis-mock/
├── turvis/
│   ├── .guard.yml                                   # Interim auth (X-Internal-Service-Token) + Phase 2 mTLS switch
│   └── POST/
│       └── v1/
│           ├── verifications/
│           │   └── xmldsig.yml                      # POST /turvis/v1/verifications/xmldsig  (W3C XML Signature)
│           ├── signatures/
│           │   └── xmldsig.yml                      # POST /turvis/v1/signatures/xmldsig     (W3C XML Signature)
│           ├── encryptions/
│           │   └── xmlenc.yml                       # POST /turvis/v1/encryptions/xmlenc     (W3C XML Encryption 1.1)
│           └── decryptions/
│               └── xmlenc.yml                       # POST /turvis/v1/decryptions/xmlenc     (W3C XML Encryption 1.1)
```

Phase 1 populates only the `xmldsig`/`xmlenc` leaves. Future format support is a drop-in sibling file (e.g. `verifications/jws.yml`) with its own schema, reason-code subset, and allowlist — no touch to existing leaves.

Current audit workspace copies at `dsl-contracts/turvis/` for review; eventual commit under `kemit-ee/efti-gate-ee` goes to `DSL/Ruuter-turvis-mock/`.

## Phase 1 — mock deployment

The DSL files are deployed as a **separate compose service** (`turvis-mock`) mirroring the `ruuter-xroad-mock` pattern. The main `ruuter` container is NOT the host — see kemit-ee#306 for the rationale (keep mock-phase trust domain separate from production Ruuter).

### Mock behavior

- Default response for every endpoint: `200 OK` with a canned accept payload matching the Phase 2 contract shape.
- Dev-mode header `X-Mock-Verdict: <reject-xxx>` triggers the matching reject response for every reason-code in the enum (see SPEC §5). Values per endpoint are listed at the top of each DSL file.
- `audit_ref` is generated per-request via a `Math.random`-based UUIDv4 expression (`crypto.randomUUID()` is not defined in Ruuter's expression engine). Not cryptographically secure — fine for a mock correlation id.
- `return_fields` (SPEC §4.0, Pattern B): mock validates **presence** (every request MUST declare it) but returns the **full** accept bundle regardless of its contents. Real Turvis in Phase 2 enforces field-level filtering + per-caller capability policy. **Well-behaved caller code reads only the fields it requested** — otherwise it may fail against real Turvis later.
- No real crypto. `/turvis/v1/signatures/xmldsig` returns the input XML pass-through; `/turvis/v1/encryptions/xmlenc` in `profile: "wss-encrypted"` returns stub XML fragments + plaintext passthrough, in `profile: "plaintext"` returns empty fragments + plaintext passthrough; `/turvis/v1/decryptions/xmlenc` returns `decrypted_soap_xml_b64` = input `soap_xml_b64` and `decrypted_attachment_b64` = input `encrypted_attachment_b64`. **Not valid on the wire** — only for integration-shape testing.
- All routes use `internal: false` (Ruuter ≥ 0.12 would 404 external POSTs on `internal: true` routes). Authn comes from the shared guard (`.guard.yml`): shared-token for Phase 1, mTLS mesh identity for Phase 2.

### CI gates (recommended before this directory is called a Phase 1 mock)

- `dsl-lint --audit` on `turvis/` — zero warnings
- `dsl-test` with one happy-path scenario per route, one reject scenario, one unknown-return-field scenario (`tests/turvis.test.yml`, run: `docker run --rm -v "$PWD:/w" -w /w turnerrainer/ruuter:0.12.1-rc dsl-test --dsl /w --tests /w/tests --constants /w/tests/constants.ini`)
- JSON Schema cross-check: every SPEC §4 required field must appear in the corresponding `allowlist:` with the same type (there's drift risk when SPEC and DSL are edited separately)

### Safety guards (must-not-ship-to-production)

- Startup check in edelivery that refuses to boot if `TURVIS_URL` points to the turvis-mock compose service AND the environment is not `dev` / `staging`.
- The `turvis-mock` compose service is not included in the production compose set / Helm chart; the deploy pipeline strips it.

## Phase 2 — real Turvis

When the real Turvis is built (external tracker, Rust/Kotlin), it MUST:

1. Accept and emit exactly the request/response shapes defined in these DSL files.
2. Honour every `reason_code` in the enum with the status code mapped at the top of each DSL.
3. Implement the real cryptography documented in SPEC §6 (W3C exc-c14n, RSA-SHA256, SHA-256 digest, RSA-OAEP, AES-128-GCM).
4. Preserve byte-fidelity on `/turvis/v1/signatures/xmldsig` output (SPEC §9.1).
5. Honour the policy carried in `required_signed_xpaths` / `required_signed_cid_refs` on verify.

### Deployment handoff

Two migration options:

**Option A — real Turvis replaces turvis-mock directly**:
- Deploy the real Turvis.
- Flip `TURVIS_URL` from turvis-mock to real.
- Drop turvis-mock from the compose set.

**Option B — Ruuter stays as HTTP front; real crypto worker behind**:
- Keep these DSL files deployed.
- Replace the mock steps (`accept`, canned response construction) with calls to a thin crypto-worker binary via `call: http.post`.
- Real Turvis becomes {DSL + crypto worker} jointly.

A/B is the dev-team's call. SPEC §13 integration contract for edelivery doesn't care which — only the HTTP contract matters.

## What Ruuter DSL `allowlist:` can and can't express

Can:
- Field presence (`required: true/false`)
- Field type (`string`, `number`, `boolean`, `array`, `object`)
- Fleet-standard regex validation (via Ruuter extension, where supported)

Can't (SPEC §4 JSON Schema fills these):
- Nested object structures (`attachments[].cid` + `attachments[].bytes_b64` per item)
- `format: base64` validation
- `format: uuid` validation
- Array minItems / maxItems
- oneOf / anyOf (accept vs problem+json response shape)
- Pattern constraints beyond simple regex
- Enum validation on strings

CI gate (recommended): a script that parses each DSL's `allowlist:` + confirms all SPEC §4 required fields appear with matching types. Catches drift when SPEC and DSL get edited separately.

## Contract-change workflow (post-Phase 1)

A field is added or renamed in SPEC §4:

1. Edit the corresponding `.yml` file in this directory (or `DSL/Ruuter-turvis-mock/` in the gate repo).
2. Edit SPEC.md §4 to match.
3. CI validates the two agree.
4. The real Turvis team sees the DSL change via git log, updates their build.
5. edelivery picks up the new field in its request/response handling.

A breaking change (removed field, type change, status-code mapping change): follow SPEC §14's `/turvis/v2/` protocol. Create a new DSL subtree `DSL/Ruuter-turvis-mock/turvis/POST/v2/` alongside `v1/`; both deployed in parallel; `v1` deprecated after 6-month window.

### Adding a new mechanism (JWS, COSE, …)

The nested `<operation>/<mechanism>` layout exists so this stays additive, not a version bump:

1. Create a new leaf file, e.g. `verifications/jws.yml`, with its own `allowlist:` (JWS has different request fields — `jws_compact_b64`, `required_protected_headers`, `critical_headers_allowlist`), its own reject responses, and its own reason-code set (`JOSE_HEADER_FORBIDDEN`, `JWS_B64_TRUE_UNSUPPORTED`, etc.).
2. Add the mechanism's reason codes to SPEC.md §5 under a dedicated subsection — do not merge them into the XMLDSig enum.
3. edelivery picks a leaf per-route (XMLDSig for AS4, JWS for a new JSON-based peer), not via content negotiation on a single endpoint.
4. Trust store, key handles, and audit path are shared across mechanisms — only crypto specifics are per-leaf.

## Links

- SPEC.md § 4 — full JSON Schemas (companion documentation for details DSL can't express)
- SPEC.md § 5 — reason-code enum
- SPEC.md § 11.2 — interim auth (X-Internal-Service-Token) + Phase 2 mesh identity
- `kemit-ee/efti-gate-ee#306` — A9-SUB-2 (where the turvis-mock compose service decision was taken)
- `kemit-ee/efti-gate-ee#305` — A9-SUB-1 (edelivery integration against these DSLs)
