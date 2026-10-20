# Turvis: Ruuter DSL contract (Phase 1 mock, Phase 2 contract source of truth)

> Turvis is the Bürostack component for security-sensitive operations (crypto, schema validation, hardened parsing). The DSL covers the **Phase 1 slice** that the eFTI gate's edelivery service integrates against: the four cryptography endpoints. Future validation / parsing endpoints will land as sibling directories (`validations/`, `parsings/`) under the same nesting pattern.

**Where things are** (the DSL used to live in this directory; #306 moved it into the service tree):

| What | Path |
|---|---|
| The four routes + guard + health probe | `DSL/Ruuter-turvis-mock/turvis/` |
| Scenarios (`dsl-test`) | `DSL-turvis-mock-tests/turvis.test.yml` |
| Ruuter config / constants / image | `ruuter-turvis-mock.yaml`, `constants-turvis-mock.ini`, `docker/turvis-mock/Dockerfile` |
| Compose service | `turvis-mock` in `compose.yml` (loopback port in `compose.override.yml`) |
| SPEC vs DSL drift check | `scripts/validate-turvis-contract.py`, unit-tested by `tests/turvis/test_validate_turvis_contract.py` |

## Decision

Turvis's REST contract is **defined by Ruuter DSL files**, not by a separate OpenAPI spec. The same DSL files serve two purposes:

1. **Phase 1 (now)**: deployed as the `turvis-mock` compose service to unblock edelivery integration (A9-SUB-1 / kemit-ee#305), while the real Turvis is still being built. **Development and test only.**
2. **Phase 2 (later)**: the authoritative request/response contract that the real Turvis MUST implement. Whoever builds the real Turvis in Rust/Kotlin reads these DSL files to know exactly what shape to emit and accept.

**SPEC.md's §4 JSON Schemas remain as companion documentation** for details Ruuter DSL `allowlist:` can't express (base64 format, oneOf accept/reject, nested object validation, enum constraints). `scripts/validate-turvis-contract.py` fails CI when the two drift (see "Checks").

## File layout

Each Turvis operation is nested under `/<operation>/<mechanism>` so sibling mechanisms (JWS/JWE per RFC 7515/7516, COSE per RFC 8152) can be added later without colliding on request/response shape or reason-code enum:

```
DSL/Ruuter-turvis-mock/
└── turvis/
    ├── .guard.yml                                   # X-Internal-Service-Token (TURVIS_INTERNAL_TOKEN) / Phase 2 mTLS switch
    ├── GET/health/{.guard.yml,ready.yml}            # public readiness probe (override_ancestors), used by the compose healthcheck
    └── POST/v1/
        ├── verifications/xmldsig.yml                # POST /turvis/v1/verifications/xmldsig  (W3C XML Signature)
        ├── signatures/xmldsig.yml                   # POST /turvis/v1/signatures/xmldsig
        ├── encryptions/xmlenc.yml                   # POST /turvis/v1/encryptions/xmlenc     (W3C XML Encryption 1.1)
        └── decryptions/xmlenc.yml                   # POST /turvis/v1/decryptions/xmlenc
```

Phase 1 populates only the `xmldsig`/`xmlenc` leaves. Future format support is a drop-in sibling file (e.g. `verifications/jws.yml`) with its own schema, reason-code subset, and allowlist, with no touch to existing leaves.

## Phase 1: the mock

A **separate compose service** (`turvis-mock`, container `efti_turvis_mock`), mirroring `ruuter-xroad-mock`: own Dockerfile, own `ruuter-turvis-mock.yaml` (port 8091, no outbound calls), no database, no dependency on the gate. The main `ruuter` container is NOT the host (A9 separates crypto into its own trust domain, kemit-ee#306). Published only on loopback: `127.0.0.1:${EFTI_TURVIS_MOCK_PORT:-8091}`. Nothing in the stack calls it yet (edelivery integration is #305), so no service depends on it.

```sh
docker compose up --build -d turvis-mock          # health: curl http://127.0.0.1:8091/turvis/health/ready
curl -X POST http://127.0.0.1:8091/turvis/v1/signatures/xmldsig \
  -H 'content-type: application/json' -H 'x-internal-service-token: dev-turvis-mock-token' \
  -d '{"soap_xml_b64":"PHg+","sign_xpaths":["//eb:Messaging"],"sign_cid_refs":[],"key_handle":"EU-EE","return_fields":["signatures"]}'
```

The token (`TURVIS_INTERNAL_TOKEN`, dedicated, not the fleet's `INTERNAL_SERVICE_TOKEN`, SPEC §11.2) and `OWN_GATE_ID` are committed in `constants-turvis-mock.ini`: a development credential, not a secret.

### What is real and what is not

**No cryptography.** `signatures/xmldsig` returns the input bytes as `signed_xml_b64` (not a valid XMLDSig); `verifications/xmldsig` validates no signature and looks up no certificate; `decryptions/xmlenc` passes the envelope and attachment through unchanged; `encryptions/xmlenc` in `wss-encrypted` returns a stub (plaintext as `encrypted_payload_b64`, fixed fake XML fragments), in `plaintext` exactly what the real Turvis returns (empty fragments, empty wrapped key, input bytes). An accept from the mock proves nothing about a real message and no security finding is closed by it.

**Real, so a consumer fails here instead of against the real Turvis:**

- `return_fields` is validated (unknown value: 422 `UNKNOWN_RETURN_FIELD`; empty array on verify/decrypt: 400) and the accept response is **projected**: the always-returned fields plus exactly the requested projectable fields (SPEC §4.0).
- `ebms_routing` (message id, conversation id, ref-to, From/To party, service, action) is extracted from the decoded envelope. A short test string with no ebMS element gets a deterministic fallback derived from the envelope bytes (djb2), so `decryptions` and `verifications` return the same ids for the same bytes. Decrypt's `ebms_routing` is diagnostic only (SPEC §4.4); verify's is authoritative.
- verify: the sender identity is the envelope's `From/PartyId`, else `claimed_signer_id`, else `EU-FR`; a `claimed_signer_id` that differs from an envelope `From` is 422 `SIGNER_IDENTITY_MISMATCH` (case-insensitive); more than one `eb:Messaging` is 422 `SIG_POLICY_FAIL`.
- verify: when the envelope carries `<ds:Reference>` elements, `signature_digests` reports them as written (original URI, `DigestValue`, transforms, `cid:message` included, XPath inferred from the referenced element) for receipt construction; a `DigestMethod` other than SHA-256 is 422 `SIG_ALGO_UNSUPPORTED`; a required XPath or cid no Reference covers is 422 `SIG_POLICY_FAIL`. Without any Reference, the references are deterministic fixtures built from the policy, identical to what `signatures/xmldsig` returns for the same policy.
- `key_handle` must equal `OWN_GATE_ID` on sign and decrypt (422 `KEY_HANDLE_UNKNOWN`); `trust_store_version`, when sent, must be `mock` (else 503 `TRUST_STORE_UNAVAILABLE`); `trace_id` must be 32 hex characters, `profile` one of the two values, attachments at most 10 `{cid: cid:<id>, bytes_b64}` (else 400).
- Statuses and content types: accept `200 application/json`; every reject `application/problem+json` (422, plus 413 `PAYLOAD_TOO_LARGE`, 503 `KEY_UNAVAILABLE`/`TRUST_STORE_UNAVAILABLE`, 500 `TURVIS_INTERNAL`, 400 for malformed input); the guard's 401 is `application/problem+json`. Bodies are unwrapped (`wrapper: false`).

**Not enforced (mock leniency):** `receiver_party_id` is not looked up and its pattern is not checked (there is no trust view; use the override header); the base64 format of the inputs is not validated; the attachment bytes are not hashed. The mock cannot refuse `AUTH_MODE=shared-token` in prod/staging (SPEC §11.2): that guard belongs to the real Turvis, and the start-up guard against pointing a production edelivery at the mock belongs to edelivery (#305).

### Test controls (headers, development only)

- `X-Mock-Verdict: reject-...` forces one reject per agreed reason code. Per route: verify `reject-xml-parse-fail`, `-xml-attack-detected`, `-sig-missing`, `-sig-crypto-fail`, `-sig-policy-fail`, `-sig-algo-unsupported`, `-signer-untrusted`, `-signer-identity-mismatch`; sign `reject-xml-parse-fail`, `-sig-build-fail`, `-key-handle-unknown`, `-key-unavailable`; encrypt `reject-receiver-unknown`, `-receiver-cert-expired`, `-receiver-cert-untrusted`, `-payload-too-large`; decrypt `reject-wrapped-key-malformed`, `-key-handle-unknown`, `-key-unavailable`, `-ski-mismatch`, `-aes-unwrap-fail`, `-payload-decrypt-fail`; all four also `reject-algo-unsupported`, `-unknown-return-field`, `-trust-store-unavailable`, `-turvis-internal`.
- `X-Mock-Audit-Ref: <value>` fixes `audit_ref`, so a test can assert an exact body. Without it `audit_ref` is a random UUIDv4 (`Math.random`; `crypto.randomUUID` is not in Ruuter's expression engine, so it is not cryptographically secure).

### Authoring notes (Ruuter expression engine)

- A response header is set with `headers:` on the return step (`Content-Type: "application/problem+json"`); a `content_type:` key on a return step is **ignored** and the response stays `application/json`.
- Values assigned in the same `assign:` block are not visible to each other; split dependent values into consecutive steps.
- Keep the number of double quote characters inside a `${...}` expression even and balanced (use `\x22` inside regular expressions and strings): the expression scanner counts them, and an odd number leaves the whole expression unevaluated, silently, as a literal string.
- The engine has no `atob`/`btoa`; the DSL decodes and encodes base64 by hand.

## Checks

- `dsl-lint --audit --dsl DSL/Ruuter-turvis-mock --constants constants-turvis-mock.ini` must report 0 errors and 0 warnings. There is no recorded exception: every declared input is read by its route (the mock-only unused inputs of the original #311 sources are now validated or consumed).
- `dsl-test` runs the scenarios; from the repository root:
  ```sh
  docker run --rm -v "$PWD:/workdir" -w /workdir turnerrainer/ruuter:0.12.1-rc \
    dsl-test --dsl DSL/Ruuter-turvis-mock --tests DSL-turvis-mock-tests --constants constants-turvis-mock.ini
  ```
  The scenarios cover each route's happy path, projection by exact body (`X-Mock-Audit-Ref`), every agreed reason code, credential rejection, plaintext profile and byte passthrough, deterministic routing/correlation across decrypt and verify, and receipt metadata including `cid:message`. Load the directory that contains `turvis/` (`--dsl DSL/Ruuter-turvis-mock`): loading `turvis/` itself drops the `/turvis` prefix.
- `python3 scripts/validate-turvis-contract.py` (needs PyYAML) compares SPEC §4/§5 with the DSL and scenarios: required request fields and types, accept-response fields, and every reason code (reject step with the §5 status, `reason code - ...` scenario).
- All three run in `.github/workflows/e2e.yml` `dsl-validate` and the mirrored GitLab `validate:dsl` job on every pull request.

### Safety (must-not-ship-to-production)

- The `turvis-mock` service is **not** built or deployed by `.gitlab-ci.yml` and has no chart in the devops repo, so no production release contains it.
- The start-up check that refuses to boot edelivery when `TURVIS_URL` points at the mock in prod/staging belongs to edelivery (#305); the mock cannot see its own environment.

## Phase 2: real Turvis

When the real Turvis is built (external tracker, Rust/Kotlin), it MUST:

1. Accept and emit exactly the request/response shapes defined in these DSL files.
2. Honour every `reason_code` in the enum with the status code mapped at the top of each DSL.
3. Implement the real cryptography documented in SPEC §6 (W3C exc-c14n, RSA-SHA256, SHA-256 digest, RSA-OAEP, AES-128-GCM).
4. Preserve byte-fidelity on `/turvis/v1/signatures/xmldsig` output (SPEC §9.1).
5. Honour the policy carried in `required_signed_xpaths` / `required_signed_cid_refs` on verify.

### Deployment handoff

**Option A: real Turvis replaces turvis-mock directly**: deploy the real Turvis, flip `TURVIS_URL`, drop `turvis-mock` from the compose set.

**Option B: Ruuter stays as HTTP front; real crypto worker behind**: keep these DSL files deployed and replace the mock steps with calls to a thin crypto-worker binary via `call: http.post`; the real Turvis becomes {DSL + crypto worker} jointly.

A/B is the dev-team's call. SPEC §13 integration contract for edelivery doesn't care which; only the HTTP contract matters.

## What Ruuter DSL `allowlist:` can and can't express

Can: field presence (`required: true/false`) and field type (`string`, `number`, `boolean`, `array`, `object`).

Can't (SPEC §4 JSON Schema fills these): nested object structures, `format: base64` / `format: uuid`, array `minItems` / `maxItems`, oneOf / anyOf (accept vs problem+json), enum validation on strings. The mock implements the ones a consumer can trip over (`minItems`, `maxItems`, the cid pattern, the `return_fields` enum) as explicit checks in the route.

## Contract-change workflow

A field is added or renamed in SPEC §4:

1. Edit the corresponding `.yml` file under `DSL/Ruuter-turvis-mock/turvis/POST/v1/`.
2. Edit SPEC.md §4 to match.
3. `scripts/validate-turvis-contract.py` (CI) validates the two agree; add or adjust the scenarios.
4. The real Turvis team sees the DSL change via the repository history and updates their build.
5. edelivery picks up the new field in its request/response handling.

A breaking change (removed field, type change, status-code mapping change): follow SPEC §14's `/turvis/v2/` protocol. Create a new DSL subtree `DSL/Ruuter-turvis-mock/turvis/POST/v2/` alongside `v1/`; both deployed in parallel; `v1` deprecated after 6-month window.

### Adding a new mechanism (JWS, COSE, …)

1. Create a new leaf file, e.g. `verifications/jws.yml`, with its own `allowlist:`, reject responses and reason-code set.
2. Add the mechanism's reason codes to SPEC.md §5 under a dedicated subsection; do not merge them into the XMLDSig enum.
3. edelivery picks a leaf per route, not via content negotiation on a single endpoint.
4. Trust store, key handles and audit path are shared across mechanisms; only crypto specifics are per leaf.

## Links

- SPEC.md § 4: full JSON Schemas (companion documentation for details DSL can't express)
- SPEC.md § 5: reason-code enum
- SPEC.md § 11.2: interim auth (X-Internal-Service-Token) + Phase 2 mesh identity
- `kemit-ee/efti-gate-ee#306`: A9-SUB-2 (this mock)
- `kemit-ee/efti-gate-ee#305`: A9-SUB-1 (edelivery integration against these DSLs)
