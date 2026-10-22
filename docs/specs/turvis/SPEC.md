# Turvis — Integration Contract

> **Turvis** is a Bürostack component focused on security-sensitive operations: hardened payload parsing, schema validation (XSD, JSON Schema, …), and cryptography (XMLDSig verify/sign, XML Encryption decrypt/encrypt). It is designed as a shared Bürostack component — any fleet service that needs these operations can call it over the same HTTP contract.
>
> **Scope of THIS document.** The Phase 1 slice of the Turvis contract that the eFTI gate's `edelivery` service consumes: the four cryptography endpoints (verify, sign, encrypt, decrypt). Payload-validation and hardened-parse endpoints are planned follow-ups under the same `/turvis/v1/<operation>/<mechanism>` URL shape (§3.1); they are **not** part of what the kemit-ee dev team integrates in #305–#308.
>
> **Audience**: the kemit-ee dev team implementing #305–#308 (edelivery integration + Phase-1 mock + perimeter trust + cutover).
>
> **Not in this document.** How Turvis implements the crypto, stores keys, rotates them, deploys, runs tests, or chooses a stack — all **Turvis's responsibility**, tracked separately when the turvis repo is provisioned (external tracker, TBD, e.g. `turnerrainer/turvis`).
>
> **Contract source of truth**: the Ruuter DSL files at `DSL/Ruuter-turvis-mock/turvis/POST/v1/*/*.yml` (served by the `turvis-mock` compose service, #306) are the AUTHORITATIVE REST contract. This document's §4 JSON Schemas are companion documentation for details Ruuter DSL `allowlist:` can't express (nested arrays, `format: base64`, oneOf accept/reject, enum constraints). See `dsl-contracts/README.md` for the contract-change workflow.
>
> **Last updated**: 2026-10-20.

---

## 0. Table of contents

1. [Context + scope](#1-context--scope)
2. [System boundary + data flow](#2-system-boundary--data-flow)
3. [Endpoint catalogue](#3-endpoint-catalogue)
4. [Request / response schemas](#4-request--response-schemas)
5. [Reason code enum](#5-reason-code-enum)
6. [Algorithm URIs (peer-interop contract)](#6-algorithm-uris-peer-interop-contract)
7. [Key material + trust store — Turvis's responsibility](#7-key-material--trust-store--turviss-responsibility)
8. [Latency budget](#8-latency-budget)
9. [Security contract (edelivery ↔ Turvis)](#9-security-contract-edelivery--sec)
10. [Observability](#10-observability)
11. [Auth between edelivery and Turvis](#11-auth-between-edelivery-and-sec)
12. [Interface stability + versioning](#12-interface-stability--versioning)
13. [Edelivery-side integration contract](#13-edelivery-side-integration-contract)
14. [Example requests + responses](#14-example-requests--responses)
15. [Glossary](#15-glossary)
16. [Non-goals](#16-non-goals)

---

## 1. Context + scope

### 1.1 What Turvis is

A standalone Bürostack HTTP service for security-sensitive operations. Phase 1 exposes four cryptography endpoints to the eFTI gate's `edelivery` service:

- **Signature verification** on inbound AS4 messages — `POST /turvis/v1/verifications/xmldsig`
- **Signature creation** on outbound AS4 messages — `POST /turvis/v1/signatures/xmldsig`
- **XML Encryption** payload encrypt — `POST /turvis/v1/encryptions/xmlenc`
- **XML Encryption** payload decrypt — `POST /turvis/v1/decryptions/xmlenc`

Later phases add payload-validation (`/turvis/v1/validations/xmlschema`, `/turvis/v1/validations/jsonschema`, …) and hardened-parse (`/turvis/v1/parsings/xml`, …) endpoints under the same URL shape. See §3.1 for the extensibility pattern. **Those endpoints are out of scope for the Phase 1 contract documented here** and are listed only so the dev team sees where the component is heading.

Turvis does **not** handle eFTI business logic, SOAP envelope assembly, peer registry CRUD, or MIME multipart framing. Those remain in `edelivery` permanently.

### 1.2 Why it exists

Security-sensitive operations and the key material / policy state they need belong behind a dedicated service boundary so that:
- consumer services (edelivery, and later any Bürostack service) hold zero crypto / key material and no custom XML parser code;
- the audit surface narrows to one well-tested component;
- key rotation, KMS integration, parser hardening, and schema policy live in one place and benefit every consumer at once.

### 1.3 Who it serves

- **Phase 1 caller**: the eFTI gate's `edelivery` service (via HTTP + mTLS).
- **Phase 2+ callers**: any Bürostack service that needs crypto, validation, or hardened parsing. Turvis is designed as a shared fleet component, not an eFTI-gate-specific sidecar.

### 1.4 Scope

**In scope (Phase 1 — this contract)**:
- Four cryptography HTTP endpoints (§3)
- Private-key access (Turvis-only secret, see §7.1)
- Read access to the generated trust view (derived from `registry/`, see §7.2)
- Audit log of every security decision
- `/metrics` endpoint
- Health probes

**Future scope (same component, later phases — not in this contract)**:
- Hardened XML/JSON parse endpoints (`/turvis/v1/parsings/xml`, `/parsings/json`, …) — a safe-parse boundary that any Bürostack service can call to turn untrusted bytes into a sanitised DOM/tree without rolling its own XXE-hardened parser
- Generic schema-validation endpoints (`/turvis/v1/validations/xmlschema`, `/validations/jsonschema`) where the **caller supplies the schema per call** (hash-cached by Turvis). NOT eFTI-specific — Turvis never registers FTI004 or any other business schema internally.
- Content-type sniffing / payload classification

**Permanently out of scope (never in Turvis)**:
- Business-schema validation (eFTI FTI004/FTI007 XSD stays in `xml-mapper` **permanently** — business concern, not security). The future `/validations/xmlschema` endpoint takes caller-supplied schemas; it does not become a registry of eFTI schemas.
- Business logic (FTI004/FTI007 semantic processing stays in edelivery / xml-mapper)
- SOAP envelope assembly (stays in edelivery — framing, not security)
- Peer registry CRUD and routing-URL management (stays in `registry/` + the `registry` service, ADR-011; Turvis is a read-only consumer of the generated trust view §7.2, never of the registry service itself)
- MIME multipart assembly on outbound AS4 (stays in edelivery — framing)
- X-Road integration
- UI
- WebSockets / SSE / streaming

**Related finding ownership** (per audit cross-reference):
- F-PV-3, F-XM-1, F-XM-2 (edelivery's XmlParser hardening): closed by **#307 perimeter trust model**, not by Turvis. edelivery's parser is explicitly unhardened by design (§9.3); hardening comes from the perimeter + Turvis as the sole parser touching peer bytes.

---

## 2. System boundary + data flow

### 2.1 Services Turvis talks to

```
                ┌───────────────────────────────┐
                │ edelivery                     │  ──┐
                │ (eFTI gate, Kotlin service)    │    │ HTTP calls over mTLS mesh
                └───────────────────────────────┘    │
                                                     ▼
                ┌─────────────────────────────────────────────────────────────────┐
                │ Turvis                                                          │
                │ (THIS CONTRACT'S COUNTERPARTY — Phase 1 exposes crypto only)    │
                │                                                                 │
                │  ┌───────────────┐ ┌───────────────┐ ┌───────────────────────┐  │
                │  │ xml-safety    │ │ xmldsig-engine│ │ schema-validators     │  │
                │  │ (hardened     │ │ (W3C XMLDSig +│ │ (XSD / JSON Schema /  │  │
                │  │  parse)       │ │  XML-Enc)     │ │  …) — future phase    │  │
                │  └───────────────┘ └───────────────┘ └───────────────────────┘  │
                └─────────────────────────────────────────────────────────────────┘
                            │                  │
                            │                  │
                            ▼                  ▼
                ┌────────────────┐   ┌────────────────────┐
                │ KMS            │   │ Trust store source │
                │ (private key)  │   │ (A3 signed         │
                │                │   │  manifest)         │
                └────────────────┘   └────────────────────┘
```

### 2.2 Inbound AS4 flow (edelivery perspective)

```
peer AS4 → Ruuter envelope checks (A8 perimeter)
       → edelivery /services/msh
       → edelivery splits multipart into SOAP bytes + attachment bytes
         (standard HTTP framework; non-crypto, non-XML-parse)
       → edelivery POSTs /turvis/v1/decryptions/xmlenc           ← Turvis
         request: {soap_xml_b64, encrypted_attachment_b64, key_handle,
                   return_fields: [decrypted_soap_xml_b64,
                                   decrypted_attachment_b64]}
         response: decrypted_soap_xml_b64 + decrypted_attachment_b64
       → edelivery POSTs /turvis/v1/verifications/xmldsig        ← Turvis
         request: {soap_xml_b64=decrypted_soap_xml_b64,
                   attachments=[decrypted_attachment_b64],
                   required_signed_xpaths, required_signed_cid_refs,
                   return_fields: [signer, ebms_routing, signature_digests,
                                   matched_signed_xpaths,
                                   matched_signed_cid_refs]}
         response: signer (verified identity) + ebms_routing (from the
                   signed <eb:Messaging>) + signature_digests (for NRR)
                   + matched_signed_* (policy acknowledgement)
       → edelivery reads ebms_routing + signer from Turvis responses
         (NO XML parse step in edelivery at any point)
       → edelivery dispatches to FTIxxxHandler by routing fields
       → handler returns response data to edelivery
```

**Key property**: edelivery never parses the SOAP envelope. All routing fields come from Turvis's responses; all security verdicts come from Turvis's responses. edelivery's only envelope-byte handling is MIME-split (standard framework) and byte-moving between HTTP calls.

### 2.3 Outbound AS4 flow (edelivery perspective)

```
edelivery handler produces response data
       → edelivery POSTs /turvis/v1/encryptions/xmlenc    ← Turvis
       → edelivery builds SOAP envelope DOM
         (splices in wrapped-key + encrypted-data XML fragments)
       → edelivery POSTs /turvis/v1/signatures/xmldsig    ← Turvis
       → edelivery assembles multipart (signed SOAP + encrypted payload)
       → edelivery POSTs to peer gate
```

### 2.4 Trust boundary

Turvis IS the trust boundary for security-sensitive operations. Everything inside is trusted (private key, trust store, parser hardening policy, schema registry); everything outside goes through the HTTP endpoints with explicit per-call policy. The Phase 1 slice exposes the four cryptography endpoints only.

Edelivery trusts turvis responses because:
1. mTLS mesh (A3) authenticates the response origin as turvis's ServiceAccount
2. NetworkPolicy egress rule on edelivery restricts outbound to turvis URL only

Edelivery does **not** independently verify turvis outputs — the perimeter trust model (A9-SUB-3) is the enforcement.

---

## 3. Endpoint catalogue

Phase 1 ships four `POST` endpoints under `/turvis/v1/<operation>/<mechanism>`, resource-named (RESTful) per CFR api_standardisation.md. All four belong to the `cryptography` family of operations:

| Method | Path | Purpose |
|---|---|---|
| POST | `/turvis/v1/verifications/xmldsig` | Verify an inbound XMLDSig (W3C XML Signature) on an AS4 SOAP envelope. |
| POST | `/turvis/v1/signatures/xmldsig` | Create an XMLDSig (W3C XML Signature) on an outbound SOAP envelope. |
| POST | `/turvis/v1/encryptions/xmlenc` | W3C XML Encryption 1.1: wrap AES key for receiver + encrypt payload. |
| POST | `/turvis/v1/decryptions/xmlenc` | W3C XML Encryption 1.1: unwrap AES key with gate's private key + decrypt payload. |

Planned follow-ups (same URL shape, **not part of this contract**):

| Method | Path | Purpose |
|---|---|---|
| POST | `/turvis/v1/parsings/xml` | Hardened XML parse; returns a sanitised DOM/tree or a reject verdict. Replaces the in-process `XmlParser` in Bürostack services that touch untrusted XML. |
| POST | `/turvis/v1/validations/xmlschema` | Validate well-formed XML against a **caller-supplied** XSD (identified by content hash; Turvis caches schemas by hash). Not a registry of pre-known schemas — eFTI's FTI004/FTI007 schemas remain in `xml-mapper`. |
| POST | `/turvis/v1/validations/jsonschema` | Validate JSON against a caller-supplied JSON Schema (same hash-cache pattern). |

These are placeholders for future revisions — not design-level commitments on the exact field names. Listed here only to anchor the §3.1 extensibility story.

### 3.1 Extensibility — why `<operation>/<mechanism>` is nested

Each format has materially different request fields, canonicalisation rules, and reject reason-codes. Collapsing them to a single `/verifications` endpoint with a `format` discriminator produces a `oneOf` body schema that Ruuter DSL `allowlist:` cannot express and a reason-code enum that is a union of disjoint sets.

Nesting isolates each spec cleanly. Two axes extend independently:

**Axis 1 — new operations under `/turvis/v1/<new-op>/`** (future families beyond cryptography):

| Operation family | Phase 1 | Future |
|---|---|---|
| `/turvis/v1/verifications/`, `/signatures/`, `/encryptions/`, `/decryptions/` | **Phase 1** — cryptography | — |
| `/turvis/v1/validations/` | — | XSD, JSON Schema, …  |
| `/turvis/v1/parsings/` | — | Hardened XML, JSON, CBOR, …  |

**Axis 2 — new mechanisms under an existing operation**:

| Operation | Phase 1 mechanism | Future siblings |
|---|---|---|
| `/turvis/v1/verifications/` | `xmldsig` (W3C XML Signature) | `jws` (RFC 7515), `cose` (RFC 8152) |
| `/turvis/v1/signatures/` | `xmldsig` (W3C XML Signature) | `jws` (RFC 7515), `cose` (RFC 8152) |
| `/turvis/v1/encryptions/` | `xmlenc` (W3C XML Encryption 1.1) | `jwe` (RFC 7516), `cose-encrypt` (RFC 8152) |
| `/turvis/v1/decryptions/` | `xmlenc` (W3C XML Encryption 1.1) | `jwe` (RFC 7516), `cose-encrypt` (RFC 8152) |

A new mechanism is a drop-in sibling file in `DSL/Ruuter-turvis-mock/turvis/POST/v1/` (e.g. `verifications/jws.yml`) with its own `allowlist:`, response schema, and reason-code set added to §5 as a dedicated subsection. A new operation family is a new top-level directory. No change to existing leaves; no `/v2/` bump. edelivery picks a leaf per-route (XMLDSig for AS4; JWS for a hypothetical JSON peer), not via content negotiation on a single endpoint.

Plus support endpoints:

| Method | Path | Purpose |
|---|---|---|
| GET | `/healthz` | Liveness probe. 200 if process is running. |
| GET | `/readyz` | Readiness probe. 200 if trust store + key handle loaded. |
| GET | `/metrics` | OpenMetrics text format for Prometheus scrape. |

All non-support endpoints:
- Request content-type: `application/json`
- Response content-type (success): `application/json`
- Response content-type (reject): `application/problem+json` (RFC 7807)
- Auth: mTLS mesh (identity = edelivery ServiceAccount)
- Idempotency: see per-endpoint §4

---

## 4. Request / response schemas

All schemas below are JSON Schema (draft 2020-12). Field types are strict — no implicit coercion.

### 4.0 Response field projection — `return_fields` (all endpoints)

Every endpoint's request body includes a **required** `return_fields` array. The caller declares which top-level **projectable** response fields it wants back; the response includes only those fields plus a per-endpoint set of **always-returned** fields (operational necessity).

**Why this pattern:**
- **Declaration-level capability**: future Turvis policy can restrict which caller SA may request which field (e.g. only receipt-building services may request `signature_digests`).
- **Explicit dependency contract**: grep the caller codebase for `return_fields:` to see exactly what the caller consumes.
- **Response-stuffing defense**: unexpected fields in Turvis's response are rejectable by the caller.
- **Forward-compatible evolution**: new projectable fields never surprise callers that weren't designed for them.

**Rules:**
- Unknown field name in `return_fields` → reject with `UNKNOWN_RETURN_FIELD` (422).
- No subfield projection (`signer.cert_ski` form). Top-level objects are atomic — request a whole subobject or omit it.
- Each endpoint's projectable enum is enumerated in §4.1–§4.4.
- **Phase 1 mock behaviour**: the mock (`DSL/Ruuter-turvis-mock/`, #306) enforces this section so that a consumer depending on a field it did not request fails against the mock, not first against the real Turvis. It validates `return_fields` (every request MUST declare it; an empty array is a 400 on verify and decrypt, where the schema has `minItems: 1`), rejects an unknown value with a real `UNKNOWN_RETURN_FIELD` (422), and **projects** the accept response: the always-returned fields plus exactly the requested projectable fields. `X-Mock-Verdict: reject-unknown-return-field` still forces the reject for tests. What the mock does not do is differ from the real Turvis only in cryptography: it validates no signature and decrypts nothing (see `dsl-contracts/README.md`).

### 4.1 `POST /turvis/v1/verifications/xmldsig`

**Purpose**: verify the inbound XMLDSig (W3C XML Signature) on an AS4 SOAP envelope + enforce signed-subtree policy. Returns signer identity, policy acknowledgement, signature digests (for NRR receipts), and `<eb:Messaging>` routing fields — all extracted by Turvis so edelivery never parses the envelope.

**Projectable fields** (opt in via `return_fields`): `signer`, `matched_signed_xpaths`, `matched_signed_cid_refs`, `signature_digests`, `ebms_routing`.
**Always returned**: `verdict`, `audit_ref`.

#### Request body schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["soap_xml_b64", "attachments", "required_signed_xpaths", "required_signed_cid_refs", "return_fields"],
  "properties": {
    "soap_xml_b64": {
      "type": "string",
      "format": "base64",
      "description": "Base64 of the SOAP envelope AFTER decrypt (output of /turvis/v1/decryptions/xmlenc.decrypted_soap_xml_b64). Exact bytes — must not be re-serialised by caller."
    },
    "attachments": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["cid", "bytes_b64"],
        "properties": {
          "cid": {
            "type": "string",
            "pattern": "^cid:[-a-zA-Z0-9._]+$",
            "description": "Content-ID of the attachment, e.g. 'cid:message'."
          },
          "bytes_b64": {
            "type": "string",
            "format": "base64",
            "description": "Base64 of attachment bytes AFTER decryption (from /turvis/v1/decryptions/xmlenc.decrypted_attachment_b64)."
          }
        }
      },
      "maxItems": 10
    },
    "required_signed_xpaths": {
      "type": "array",
      "items": {"type": "string"},
      "minItems": 1,
      "description": "XPath expressions identifying DOM nodes that MUST be covered by the signature. e.g. ['//eb:Messaging', '//env:Body']."
    },
    "required_signed_cid_refs": {
      "type": "array",
      "items": {"type": "string", "pattern": "^cid:[-a-zA-Z0-9._]+$"},
      "description": "Content-ID references that MUST appear in SignedInfo. e.g. ['cid:message']."
    },
    "return_fields": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "string",
        "enum": ["signer", "matched_signed_xpaths", "matched_signed_cid_refs", "signature_digests", "ebms_routing"]
      },
      "uniqueItems": true,
      "description": "Which auxiliary fields to include in the accept response. verdict + audit_ref are always returned. Unknown values → reject with UNKNOWN_RETURN_FIELD."
    },
    "claimed_signer_id": {
      "type": "string",
      "pattern": "^[a-zA-Z0-9][a-zA-Z0-9_\\-]{0,63}$",
      "description": "OPTIONAL. If provided, Turvis checks that the signer cert's identity matches this value and rejects with SIGNER_IDENTITY_MISMATCH on mismatch. If omitted, Turvis extracts <eb:From>/<eb:PartyId> from the envelope and uses it. Formal cross-border gate IDs follow `^[A-Z]{2}-[A-Z0-9]{2,8}$` (EU-FR, EU-EE); the broader pattern here accommodates dev/test party IDs (mock, mock-edelivery, pikker). Production deploys may enforce the stricter pattern via a separate CI gate (see §13.10)."
    },
    "trust_store_version": {
      "type": "string",
      "description": "Optional. If caller wants verification against a specific trust-view generation (§7.2 `generation`). Omit for default; a mismatch is rejected with TRUST_STORE_UNAVAILABLE."
    },
    "trace_id": {
      "type": "string",
      "pattern": "^[0-9a-f]{32}$",
      "description": "W3C trace ID (hex 32-char). Propagated to audit log + logs."
    }
  }
}
```

#### Response — accept (200 OK)

Always returned: `verdict`, `audit_ref`. Other fields appear only if named in request's `return_fields`.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["verdict", "audit_ref"],
  "properties": {
    "verdict": {"const": "accept"},
    "audit_ref": {
      "type": "string",
      "format": "uuid",
      "description": "Opaque UUID for this verification decision. Lookup via GET /turvis/v1/audit/{audit_ref}."
    },
    "signer": {
      "type": "object",
      "description": "Projectable. Present only if 'signer' ∈ return_fields.",
      "required": ["cert_subject_dn", "cert_ski", "cert_serial", "verified_identity"],
      "properties": {
        "cert_subject_dn": {"type": "string"},
        "cert_ski": {"type": "string", "description": "Hex-encoded Subject Key Identifier."},
        "cert_serial": {"type": "string", "description": "Decimal serial number."},
        "verified_identity": {
          "type": "string",
          "description": "The party ID this signature was verified as (authoritative — derived from signer cert)."
        }
      }
    },
    "matched_signed_xpaths": {
      "type": "array",
      "items": {"type": "string"},
      "description": "Projectable. Subset of required_signed_xpaths that were covered by SignedInfo."
    },
    "matched_signed_cid_refs": {
      "type": "array",
      "items": {"type": "string"},
      "description": "Projectable."
    },
    "signature_digests": {
      "type": "array",
      "description": "Projectable. One entry per <ds:Reference> of the verified signature, in document order. Used by edelivery to build signed non-repudiation receipts (NRR) without re-computing crypto: the receipt references the same URIs, digests and transforms as the received signature. An XPath alone cannot reconstruct a Reference, hence reference_uri and transforms. Empty array in plaintext mode (no signature present).",
      "items": {
        "type": "object",
        "required": ["reference_uri", "digest_b64", "algorithm", "transforms", "reference_xml_b64"],
        "properties": {
          "reference_uri": {"type": "string", "description": "The original <ds:Reference>/@URI, e.g. '#MSG-ID', '#BODY-ID' or 'cid:message'."},
          "reference_xml_b64": {"type": "string", "format": "base64", "description": "Base64 of the UTF-8 bytes of the received <ds:Reference> element exactly as the peer wrote it (DigestMethod, every Transform with its parameters such as InclusiveNamespaces PrefixList, attribute order, quoting and whitespace unchanged), made self-contained: the only permitted difference from the received bytes is a namespace declaration added to the element's start tag where the element relied on one declared by an ancestor. Exactly one Reference element whose start tag declares the xmldsig namespace itself and whose `URI` equals `reference_uri`, no comment, processing instruction, DOCTYPE or CDATA, at most 64 KiB; a namespace prefix used inside the element but declared on an ancestor is declared on the element too. edelivery repeats these bytes verbatim in the NRR receipt it builds and never rebuilds a Reference from the other fields, so a peer that compares transform parameters or the digest method sees what it sent. The other fields stay for correlation and policy checks."},
          "subtree_xpath": {"type": "string", "description": "XPath of the referenced XML subtree. Absent for attachment (cid:) references."},
          "digest_b64": {"type": "string", "format": "base64"},
          "algorithm": {"type": "string", "enum": ["SHA-256"]},
          "transforms": {"type": "array", "items": {"type": "string"}, "description": "Transform algorithm URIs of the Reference, in order (e.g. exc-c14n, SwA attachment-content). Empty array if none."}
        }
      }
    },
    "ebms_routing": {
      "type": "object",
      "description": "Projectable. ebMS 3.0 routing + correlation fields. AUTHORITATIVE: Turvis extracts them from the exact <eb:Messaging> element whose <ds:Reference> was validated by this signature, never from another matching element. If the envelope has more than one <eb:Messaging>, or the one present is not covered by a Reference, the call rejects with SIG_POLICY_FAIL. edelivery uses these fields for dispatch and correlation without parsing the envelope.",
      "required": ["message_id", "from_party_id", "to_party_id", "service", "action"],
      "properties": {
        "message_id": {"type": "string"},
        "conversation_id": {"type": "string"},
        "ref_to_message_id": {"type": "string", "description": "Empty string if no correlation."},
        "from_party_id": {"type": "string"},
        "to_party_id": {"type": "string"},
        "service": {"type": "string"},
        "action": {"type": "string"}
      }
    }
  }
}
```

#### Response — reject (422 Unprocessable Entity, RFC 7807 problem+json)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["type", "title", "status", "verdict", "reason_code", "audit_ref"],
  "properties": {
    "type": {"const": "https://efti.example/problem/turvis-verify-xmldsig-reject"},
    "title": {"type": "string"},
    "status": {"const": 422},
    "verdict": {"const": "reject"},
    "reason_code": {"$ref": "#/$defs/reasonCode"},
    "detail": {"type": "string", "description": "Human-readable; must not include raw XML or key material."},
    "audit_ref": {"type": "string", "format": "uuid"}
  }
}
```

#### Idempotency

Pure function of inputs. Same request → identical response (modulo `audit_ref`). Safe to retry.

### 4.2 `POST /turvis/v1/signatures/xmldsig`

**Purpose**: create an XMLDSig signature on an outbound SOAP envelope.

**Projectable fields** (opt in via `return_fields`): `signatures`.
**Always returned**: `signed_xml_b64`, `audit_ref`.

**Input parsing**: Turvis parses `soap_xml_b64` namespace-aware, with DTDs and external entities disabled. Input that is not namespace-well-formed XML 1.0 (unbalanced or mismatched tags, an unbound prefix, an undefined entity or character reference, a DOCTYPE) is rejected with `XML_PARSE_FAIL` and nothing is signed. edelivery relies on this when it splices a peer's `ds:Reference` (§4.1 `reference_xml_b64`) into a receipt: it only checks the fragment's shape, not its well-formedness.

#### Request body schema

```json
{
  "type": "object",
  "required": ["soap_xml_b64", "sign_xpaths", "sign_cid_refs", "key_handle", "return_fields"],
  "properties": {
    "soap_xml_b64": {
      "type": "string",
      "format": "base64",
      "description": "Base64 of unsigned SOAP envelope (eFTI shape, edelivery-authored)."
    },
    "sign_xpaths": {
      "type": "array",
      "items": {"type": "string"},
      "minItems": 1,
      "description": "XPath nodes to sign, e.g. ['//eb:Messaging', '//env:Body']."
    },
    "sign_cid_refs": {
      "type": "array",
      "items": {"type": "string", "pattern": "^cid:[-a-zA-Z0-9._]+$"},
      "description": "Content-IDs for SwA attachment signing."
    },
    "key_handle": {
      "type": "string",
      "description": "Fixed per gate = OWN_GATE_ID. See §7."
    },
    "return_fields": {
      "type": "array",
      "items": {"type": "string", "enum": ["signatures"]},
      "uniqueItems": true,
      "description": "Which auxiliary fields to include. signed_xml_b64 + audit_ref are always returned. Empty array is valid (then only the always-returned fields come back)."
    },
    "attachment_bytes_b64": {
      "type": "array",
      "description": "If sign_cid_refs is non-empty, this provides the attachment bytes for digest computation.",
      "items": {
        "type": "object",
        "required": ["cid", "bytes_b64"],
        "properties": {
          "cid": {"type": "string"},
          "bytes_b64": {"type": "string", "format": "base64"}
        }
      }
    },
    "trace_id": {"type": "string", "pattern": "^[0-9a-f]{32}$"}
  }
}
```

#### Response — accept (200 OK)

```json
{
  "type": "object",
  "required": ["signed_xml_b64", "audit_ref"],
  "properties": {
    "signed_xml_b64": {
      "type": "string",
      "format": "base64",
      "description": "Base64 of the SIGNED SOAP envelope. edelivery MUST send these bytes to the peer AS-IS (no re-serialisation, no re-canonicalisation)."
    },
    "audit_ref": {"type": "string", "format": "uuid"},
    "signatures": {
      "type": "array",
      "description": "Projectable. Per-subtree digest list for logging/audit. The actual signature is embedded in signed_xml_b64 bytes; this is metadata.",
      "items": {
        "type": "object",
        "properties": {
          "reference_uri": {"type": "string", "description": "The <ds:Reference>/@URI created by this signature."},
          "subtree_xpath": {"type": "string", "description": "Absent for attachment (cid:) references."},
          "digest_b64": {"type": "string"},
          "algorithm": {"type": "string"},
          "transforms": {"type": "array", "items": {"type": "string"}}
        }
      }
    }
  }
}
```

#### Response — reject (422 problem+json)

Same shape as verify reject, with `type: "https://efti.example/problem/turvis-sign-xmldsig-reject"`.

#### Signature placement

The unsigned envelope edelivery sends holds exactly one `<wsse:Security>` header (with the encryption fragments, empty in the plaintext profile). Turvis inserts exactly one `<ds:Signature>` as the last child of that existing `wsse:Security` header and changes no other byte of the envelope, so everything before the insertion point and everything after it is byte-for-byte the input. edelivery checks this on the returned bytes without parsing them and refuses a response that differs (a protocol error, counted as `turvis_protocol`).

#### Idempotency

**Byte-identical under retry.** XMLDSig with RSA-SHA256 (PKCS#1 v1.5, deterministic) + fixed canonicalisation input produces the same signed bytes for the same input. edelivery MAY dedup retries if desired.

### 4.3 `POST /turvis/v1/encryptions/xmlenc`

**Purpose**: WS-Encryption of outbound payload for a specific receiver. Supports both encrypted (`profile: "wss-encrypted"`) and plaintext (`profile: "plaintext"`) output modes so edelivery has a single code path for both.

**Projectable fields** (opt in via `return_fields`): `wrapped_key_xml`, `encrypted_data_xml`, `wrapped_key_b64`.
**Always returned**: `encrypted_payload_b64`, `audit_ref`.

#### Request body schema

```json
{
  "type": "object",
  "required": ["plaintext_bytes_b64", "receiver_party_id", "profile", "return_fields"],
  "properties": {
    "plaintext_bytes_b64": {
      "type": "string",
      "format": "base64",
      "description": "Base64 of the gzip-compressed business payload (edelivery compresses before calling)."
    },
    "receiver_party_id": {
      "type": "string",
      "pattern": "^[A-Z]{2}-[A-Z0-9]{2,8}$",
      "description": "Target party. Turvis looks up receiver cert in trust store."
    },
    "profile": {
      "type": "string",
      "enum": ["wss-encrypted", "plaintext"],
      "description": "'wss-encrypted' = real RSA-OAEP + AES-GCM output (Phase 2); 'plaintext' = empty XML fragments + input bytes passthrough so edelivery's splicing is a no-op. edelivery picks per outbound route config."
    },
    "return_fields": {
      "type": "array",
      "items": {"type": "string", "enum": ["wrapped_key_xml", "encrypted_data_xml", "wrapped_key_b64"]},
      "uniqueItems": true,
      "description": "Which auxiliary fields to include. encrypted_payload_b64 + audit_ref are always returned."
    },
    "trace_id": {"type": "string", "pattern": "^[0-9a-f]{32}$"}
  }
}
```

#### Response — accept (200 OK)

```json
{
  "type": "object",
  "required": ["encrypted_payload_b64", "audit_ref"],
  "properties": {
    "encrypted_payload_b64": {
      "type": "string",
      "format": "base64",
      "description": "wss-encrypted mode: Base64 of AES-128-GCM(ephemeral_aes_key, iv || ciphertext || tag). plaintext mode: input plaintext_bytes_b64 passthrough. edelivery MIME-wraps either as attachment."
    },
    "audit_ref": {"type": "string", "format": "uuid"},
    "wrapped_key_b64": {
      "type": "string",
      "description": "Projectable. wss-encrypted mode: Base64 of RSA-OAEP(receiver_cert, ephemeral_aes_key). plaintext mode: empty string."
    },
    "wrapped_key_xml": {
      "type": "string",
      "description": "Projectable. wss-encrypted mode: the <xenc:EncryptedKey> XML block ready to splice into SOAP header. plaintext mode: empty string — edelivery's splicing is a no-op."
    },
    "encrypted_data_xml": {
      "type": "string",
      "description": "Projectable. wss-encrypted mode: the <xenc:EncryptedData> XML block. plaintext mode: empty string."
    }
  }
}
```

#### Response — reject (422 problem+json)

Reasons: `RECEIVER_UNKNOWN`, `RECEIVER_CERT_EXPIRED`, `RECEIVER_CERT_UNTRUSTED`, `PAYLOAD_TOO_LARGE`, `ALGO_UNSUPPORTED`, `UNKNOWN_RETURN_FIELD`.

#### Idempotency

**NOT byte-equal under retry in wss-encrypted mode.** Each call generates a fresh ephemeral AES key + random IV. Each response is a valid WS-Encryption envelope for the same plaintext. edelivery must handle retries as "new call" (don't dedup). In plaintext mode, output is byte-identical under retry (passthrough).

### 4.4 `POST /turvis/v1/decryptions/xmlenc`

**Purpose**: WS-Encryption decrypt of inbound SOAP envelope + attachment with gate's private key. Turvis extracts the wrapped AES key from `<xenc:EncryptedKey>` and the recipient SKI from `<wsse:KeyIdentifier>` **itself** — edelivery passes the raw envelope and never parses crypto elements. Also handles plaintext (unencrypted) inbound messages via passthrough.

**Projectable fields** (opt in via `return_fields`): `decrypted_soap_xml_b64`, `decrypted_attachment_b64`, `ebms_routing`.
**Always returned**: `audit_ref`.

(`decrypted_soap_xml_b64` + `decrypted_attachment_b64` are projectable because some callers may want only `ebms_routing` — e.g. a routing-only pre-check — without the body of the message. Default usage requests both.)

#### Request body schema

```json
{
  "type": "object",
  "required": ["soap_xml_b64", "key_handle", "return_fields"],
  "properties": {
    "soap_xml_b64": {
      "type": "string",
      "format": "base64",
      "description": "Base64 of the raw inbound SOAP envelope. Turvis extracts <wsse:Security>/<xenc:EncryptedKey> and <wsse:KeyIdentifier> internally. If no <xenc:EncryptedData> is present (plaintext mode), passthrough."
    },
    "encrypted_attachment_b64": {
      "type": "string",
      "format": "base64",
      "description": "Raw encrypted attachment bytes. Omit if the message has no attachment. In plaintext mode, this is the plaintext attachment and passes through unchanged."
    },
    "key_handle": {
      "type": "string",
      "description": "Fixed per gate = OWN_GATE_ID."
    },
    "return_fields": {
      "type": "array",
      "minItems": 1,
      "items": {"type": "string", "enum": ["decrypted_soap_xml_b64", "decrypted_attachment_b64", "ebms_routing"]},
      "uniqueItems": true,
      "description": "Which fields to include. audit_ref is always returned."
    },
    "trace_id": {"type": "string", "pattern": "^[0-9a-f]{32}$"}
  }
}
```

#### Response — accept (200 OK)

```json
{
  "type": "object",
  "required": ["audit_ref"],
  "properties": {
    "audit_ref": {"type": "string", "format": "uuid"},
    "decrypted_soap_xml_b64": {
      "type": "string",
      "format": "base64",
      "description": "Projectable. Base64 of the SOAP envelope with <xenc:EncryptedData> replaced by the decrypted plaintext element. In plaintext mode: input soap_xml_b64 passthrough."
    },
    "decrypted_attachment_b64": {
      "type": "string",
      "format": "base64",
      "description": "Projectable. Base64 of the decrypted (and gzip-compressed) business payload bytes. Same gzip convention as the sender used before encryption, in both wss-encrypted and plaintext modes. Empty string if the message has no attachment."
    },
    "ebms_routing": {
      "type": "object",
      "description": "Projectable. Same shape as §4.1 verify response ebms_routing, but DIAGNOSTIC ONLY: extracted from bytes whose signature has not been verified yet. It MUST NOT authorise dispatch, party lookup or business correlation; those use the ebms_routing of the verify response.",
      "required": ["message_id", "from_party_id", "to_party_id", "service", "action"],
      "properties": {
        "message_id": {"type": "string"},
        "conversation_id": {"type": "string"},
        "ref_to_message_id": {"type": "string"},
        "from_party_id": {"type": "string"},
        "to_party_id": {"type": "string"},
        "service": {"type": "string"},
        "action": {"type": "string"}
      }
    }
  }
}
```

#### Response — reject (422 problem+json)

Reasons: `WRAPPED_KEY_MALFORMED`, `KEY_HANDLE_UNKNOWN`, `SKI_MISMATCH`, `AES_UNWRAP_FAIL`, `PAYLOAD_DECRYPT_FAIL`, `ALGO_UNSUPPORTED`, `UNKNOWN_RETURN_FIELD`.

#### Idempotency

Pure function. Same request → identical response (modulo `audit_ref`). Safe to retry.

---

## 5. Reason code enum

Complete set across all four endpoints. Callers map each to a specific SOAP fault code + log line + metric increment.

| Reason code | HTTP status | Endpoints where it applies | Meaning |
|---|---|---|---|
| `XML_PARSE_FAIL` | 422 | verifications, signatures | Input XML malformed (bytes won't parse to a well-formed DOM). |
| `XML_ATTACK_DETECTED` | 422 | verifications | Parser hit a size/depth/entity-expansion limit before completing parse. Hostile input signature. |
| `SIG_MISSING` | 422 | verifications | No `<ds:Signature>` element found. |
| `SIG_CRYPTO_FAIL` | 422 | verifications | `signature.validate()` returned false. Digest mismatch or key mismatch. |
| `SIG_POLICY_FAIL` | 422 | verifications | Required subtree (per `required_signed_xpaths` / `required_signed_cid_refs`) not present in `SignedInfo.references`. |
| `SIG_ALGO_UNSUPPORTED` | 422 | verifications | Signature uses algorithm not in allowlist (RSA-SHA256 only). |
| `SIGNER_UNTRUSTED` | 422 | verifications | Signer cert does not chain to trust store. |
| `SIGNER_IDENTITY_MISMATCH` | 422 | verifications | Signer cert's SKI/Subject does not match `claimed_signer_id`. |
| `SIG_BUILD_FAIL` | 422 | signatures | Internal error during XMLDSig construction. Rare; indicates library bug or input edge case. |
| `KEY_HANDLE_UNKNOWN` | 422 | signatures, decryptions | `key_handle` not registered. |
| `KEY_UNAVAILABLE` | 503 | signatures, decryptions | KMS temporarily unreachable; transient, retryable per §13.3. |
| `WRAPPED_KEY_MALFORMED` | 422 | decryptions | RSA-OAEP ciphertext malformed. |
| `SKI_MISMATCH` | 422 | decryptions | SKI from inbound `<wsse:KeyIdentifier>` does not match the SKI of the key behind `key_handle`. |
| `AES_UNWRAP_FAIL` | 422 | decryptions | RSA-OAEP decrypt returned error. |
| `PAYLOAD_DECRYPT_FAIL` | 422 | decryptions | AES-GCM decrypt returned error (likely integrity tag mismatch — payload tampered). |
| `RECEIVER_UNKNOWN` | 422 | encryptions | `receiver_party_id` absent from the trust view or not `ONLINE` there (detail names which). |
| `RECEIVER_CERT_EXPIRED` | 422 | encryptions | Receiver cert past `notAfter`. |
| `RECEIVER_CERT_UNTRUSTED` | 422 | encryptions | Receiver cert does not chain to trust store. |
| `PAYLOAD_TOO_LARGE` | 413 | encryptions | Payload exceeds `MAX_PAYLOAD_BYTES` (default 50 MiB). |
| `UNKNOWN_RETURN_FIELD` | 422 | all | `return_fields` contains a value not in the endpoint's projectable set (see §4.0 + per-endpoint enum). Caller typo; code must align with SPEC. |
| `ALGO_UNSUPPORTED` | 422 | all | Fallback for any algorithm choice not in the pinned allowlist. |
| `TRUST_STORE_UNAVAILABLE` | 503 | all | No usable trust snapshot; transient, retryable per §13.3 (including `encryptions`: a 503 reject never carries ciphertext). |
| `TURVIS_INTERNAL` | 500 | all | Internal bug. Not retried automatically (§13.3). |

Callers (edelivery) must handle every code. Unknown codes = treat as `SIG_CRYPTO_FAIL` equivalent (reject).

---

## 6. Algorithm URIs (peer-interop contract)

Turvis's internal crypto implementation is **Turvis's responsibility**. The algorithm URIs below are listed because they are the wire-level contract with peer gates — if Turvis deviates from them, peers running Apache Santuario / Domibus / Holodeck will fail to verify signatures or decrypt payloads.

### 6.1 XMLDSig

| Field | Value |
|---|---|
| Canonicalisation | `http://www.w3.org/2001/10/xml-exc-c14n#` (exc-c14n) |
| Signature method | `http://www.w3.org/2001/04/xmldsig-more#rsa-sha256` (RSA-SHA256, PKCS#1 v1.5) |
| Digest method | `http://www.w3.org/2001/04/xmlenc#sha256` (SHA-256) |
| Transforms (allowlist) | exc-c14n (as above); `http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Signature-Transform` (SwA attachment-content). Any other → `SIG_ALGO_UNSUPPORTED`. |
| Signature mode | enveloped only |
| KeyInfo | `<wsse:SecurityTokenReference><wsse:KeyIdentifier>` carrying X509 SKI (Base64) |

### 6.2 WS-Encryption

| Field | Value |
|---|---|
| Key wrap | `http://www.w3.org/2009/xmlenc11#rsa-oaep` (RSA-OAEP, MGF1-SHA256, digest SHA-256) |
| Payload encryption | `http://www.w3.org/2009/xmlenc11#aes128-gcm` (AES-128-GCM, 96-bit IV, 128-bit tag, serialised as `iv \|\| ciphertext \|\| tag`) |

Why dev team needs this: these URIs are what Turvis MUST emit on outbound sign/encrypt and MUST accept on inbound verify/decrypt. If an inbound AS4 message carries a different URI, Turvis rejects with `SIG_ALGO_UNSUPPORTED` / `ALGO_UNSUPPORTED` and edelivery turns that into an AS4 fault to the peer.

---

## 7. Key material + trust store

### 7.1 Private key — **Turvis's responsibility**

The gate's own private key is held exclusively inside Turvis (or a KMS behind it). edelivery has no access. Format, handover procedure, rotation runbook, and KMS integration are all internal to Turvis.

**What edelivery needs to know**:
- `key_handle` field (passed on every `/signatures/xmldsig` + `/decryptions/xmlenc` call): **fixed per gate = `OWN_GATE_ID`** (e.g. `EU-EE`). Turvis rejects with `KEY_HANDLE_UNKNOWN` on mismatch. No multi-key support in v1.

### 7.2 Trust view generated from `registry/` (single source of truth)

> **Status: agreed** (kemit-ee#304 merge condition 1; approved on PR #311). This section replaces the earlier separately maintained `parties.yml` design. The ADR-011 amendment recording this design is [ADR-011, "Täiendus: Turvise usaldusvaade"](../../architecture/decisions/011-registries-as-signed-config.md) (kemit-ee#316); the `registry-to-json.py` extension that emits `turvis-trust.json` is tracked in kemit-ee#315; the Phase-2 integrity mechanism (ADR-011 §2 release signing vs an A3 signed manifest) is left open.

**Authoritative source.** `registry/` (ADR-011) stays the only human-edited store of peer identity, status and certificates. There is no second hand-maintained peer file. Turvis's trust configuration is **generated** from `registry/gates/*.yml` (and the optional `registry/trust-anchors/*.pem`) at build time.

**Who reads what.**

| Consumer | Reads | Does not read |
|---|---|---|
| edelivery | `registry` service (unchanged, ADR-011): `status`, `eDeliveryUrl`, `eDeliveryCert` for the AS4 party map and routing | the trust view |
| Turvis | the generated **trust view** (below) | the `registry` service, platform entries, `apiKey`, `headers`, URLs, any other credential |

edelivery keeps `RegistryClient` and its 60 s refresh; nothing about routing moves. Turvis never gains network access to the `registry` service, which also serves `platforms.json` with plaintext `apiKey` values (ADR-011 §5).

**Generation (build time, allowlist-based).**
- `scripts/registry-to-json.py` (already converter and validator, ADR-011 §3) gains a second output, `turvis-trust.json`. Fields are copied by **allowlist**, never by denylist: `party_id` (the registry `id`), `status`, `verified_identity`, `cert_pem` (= `eDeliveryCert`), `cert_ski`, `cert_subject_dn`, `valid_until` (derived from the certificate), and `trust_anchors[]`.
- Only `ONLINE` and `DISABLED` exist (the registry has no `OFFLINE`); `DISABLED` entries are kept in the view so Turvis can answer with a precise detail line instead of "unknown".
- A gate **without** `eDeliveryCert` (optional in the registry) is **left out of the view**, exactly as edelivery leaves it out of the AS4 party map, so both sides deny it (ADR-011 amendment, "`eDeliveryCert` jääb valikuliseks"). `registry/trust-anchors/*.pem` is accepted by the converter only under that directory.
- Build fails if: an existing `eDeliveryCert` does not parse; a certificate (gate or trust anchor) is already expired or not yet valid (`notBefore` in the future); an `allowed_chain_anchors` alias names no file in `registry/trust-anchors/`; `verified_identity` is duplicated; the output contains any key outside the allowlist (a unit test asserts `apiKey`, `headers`, `tlsCert` and URLs never appear).
- The view is packaged as its own small static artifact (same build, same git SHA, separate image) and delivered to Turvis by image/volume, not through the apiKey-bearing `registry` image.

```json
{
  "schema_version": "1.0",
  "generation": "sha256:9f2c…",
  "source_revision": "git:ce32e3e",
  "trust_anchors": [{"alias": "efti-root-ca-2026", "cert_pem": "-----BEGIN CERTIFICATE-----\n…"}],
  "parties": [
    {"party_id": "EU-FR", "status": "ONLINE", "verified_identity": "EU-FR",
     "cert_pem": "-----BEGIN CERTIFICATE-----\n…", "cert_ski": "a1b2c3…",
     "cert_subject_dn": "CN=EU-FR-gate,O=DGITM,C=FR", "valid_until": "2027-10-15T00:00:00Z",
     "allowed_chain_anchors": []}
  ]
}
```

`generation` is the SHA-256 of the canonical `parties`+`trust_anchors` content. The git revision is informational.

**Pinning vs chain (explicit).** Both modes exist per party, and the mode is data-driven:
- `allowed_chain_anchors: []` → the signer certificate must equal `cert_pem` (**pinning**, today's `singletonKeySelector` semantics per peer). This is the default produced from the registry in Phase 1.
- `allowed_chain_anchors: [alias,…]` → the certificate must chain to one of those anchors (**chain of trust**, §9.4). Introduced per party when eFTI PKI governance supplies anchors.

**Integrity.**
- Phase 1: Turvis recomputes `generation` on load and refuses a file whose hash does not match. The artifact itself is bound to the deployment by image digest (provenance via the existing SBOM/trivy pipeline).
- Phase 2: a detached signature over `generation` (ADR-011 §2 release-time signing, or the A3 signed manifest) is verified against a configured anchor before loading. This is **not implemented yet** in the repo; it is a stated requirement, not a current control.
- On any verification or parse failure Turvis keeps the previous snapshot and answers `TRUST_STORE_UNAVAILABLE` only if no snapshot was ever loaded (fail-closed at start-up; see §7.3).

**Scope boundary.** Not in the view: our own private key (Turvis-only secret mount), the TLS CA bundle for outbound HTTPS (stays with edelivery or the mesh), internal tokens, mesh material, platform credentials.

### 7.3 Reload semantics

- Delivery is image- or volume-based; Turvis re-reads the artifact on change (file watch) or at `TRUST_STORE_REFRESH_SECONDS` (default 60, matching edelivery's `REGISTRY_REFRESH_SECONDS`).
- In-flight requests finish against the snapshot they started with; the next request uses the new one. No mid-request switch.
- **Load failure after a successful earlier load**: the current snapshot stays in use, `turvis_trust_store_load_failed_total` increments, and the divergence alert (§7.4) fires if the failure persists.
- **Load failure with no snapshot** (first start): `/readyz` stays non-ready and crypto endpoints answer `TRUST_STORE_UNAVAILABLE`.
- A registry edit applies only after the registry **and** trust artifacts are rebuilt and deployed (ADR-011: a restart alone applies nothing).

### 7.4 Consistency, revocation and divergence

Sharing a source does not make two pods agree on the generation they have loaded. The contract therefore makes generations visible and divergence an alert, not an assumption.

**Visible generations.**
- Turvis exports `turvis_trust_generation_info{generation="…"} 1` and returns `X-Turvis-Trust-Generation` on every response; edelivery logs it with the `audit_ref`.
- edelivery exports the generation of its loaded registry as `edelivery_registry_generation_info{generation="…"} 1`. The registry artifact and the trust artifact are built from the same revision, so their `generation` values are derived from the same content hash for the shared party fields.
- **Alert**: more than one distinct `generation` among Turvis replicas, or Turvis and edelivery on different generations, for longer than 5 minutes. A rolling deploy produces short, expected overlap.

**Revocation and status (deny wins).** A party is usable only if **both** views say `ONLINE`. Neither side may widen the other's decision.

| edelivery registry | Turvis trust view | Inbound (verify) | Outbound |
|---|---|---|---|
| `ONLINE` | `ONLINE` | accepted | sent |
| `DISABLED` | `ONLINE` | verify accepts; edelivery rejects the sender as for an unknown id (ADR-011: `DISABLED` is a full stop) | not in AS4 party map; no Turvis call |
| `ONLINE` | `DISABLED` / absent | `SIGNER_UNTRUSTED` (detail names the status) | `RECEIVER_UNKNOWN` → `OUTBOUND_FAILED` (§13.3a) + divergence alert |
| `DISABLED` | `DISABLED` | `SIGNER_UNTRUSTED` | not sent |

A row with different statuses is a **generation mismatch**, resolved by deploying the matching artifacts; it is never resolved by trusting the more permissive side.

**While generations differ (deploy gap, replica difference).** A temporary difference is accepted and bounded by the 5-minute alert below; the request protocol carries no expected generation. Each side decides on its own view, the table above applies, each Turvis replica answers from its own snapshot and returns `X-Turvis-Trust-Generation`, which edelivery logs with the `audit_ref`. Residual risk: during a certificate rotation a pinned peer may be rejected (`SIGNER_UNTRUSTED`) by a replica still on the old certificate, and `DISABLED` takes full effect only once all replicas have the new generation. See ADR-011 amendment.

**Certificate rotation.** Rotating a peer certificate changes `cert_pem` in `registry/gates/<id>.yml`; one build yields both artifacts. During the overlap a peer may sign with either generation's certificate, so rotation of a pinned party is performed by listing the new certificate in the same change and removing the old one after the peer cut over. Expiry is monitored by `turvis_trust_party_days_to_expiry`.

**Own certificate.** The private key lives only in Turvis. Turvis exposes `turvis_own_cert_info{ski="…"}` and `turvis_own_cert_days_to_expiry` for the key behind `key_handle`. A deploy-time check compares the SKI with `cert_ski` of `registry/gates/<OWN_GATE_ID>.yml`, which is the certificate peers receive; a mismatch blocks promotion.

**Drift sources and controls.**
- Registry vs trust view: impossible within one build (same files, one script); detected across deployments by the generation alert above.
- Trust view vs upstream PKI manifest (Phase 2): deploy pipeline compares them and refuses promotion on mismatch.
- Mock vs real: `turvis-mock` has no trust view; it is exercised via `X-Mock-Verdict` only (§13.0).

**Network effect (#309).** Turvis needs no egress to the `registry` service. If the trust artifact is delivered by volume it needs no network path at all; if by HTTP, only its own artifact service.

---

## 8. Latency budget

Per-call p99 budget at the turvis process boundary (not including mesh / network):

| Endpoint | Payload size | p99 budget |
|---|---|---|
| `/turvis/v1/verifications/xmldsig` | 10 KB envelope + 1 MB attachment | 150 ms |
| `/turvis/v1/signatures/xmldsig` | 10 KB envelope + 1 MB attachment | 100 ms |
| `/turvis/v1/encryptions/xmlenc` | 1 MB plaintext | 100 ms |
| `/turvis/v1/decryptions/xmlenc` | 1 MB ciphertext | 100 ms |

Why dev team needs this: the peer-gate G2G chain tolerates ~65–70s end-to-end. Each AS4 message triggers 2 sec calls on inbound + 2 on outbound = up to 4 × 150 ms = 600 ms of sec overhead per round-trip. edelivery's per-call HTTP timeout (§13.3) sits above this budget.

Throughput, availability, error-rate SLOs, replica count, CPU/memory sizing — all **Turvis's responsibility**.

---

## 9. Security contract (edelivery ↔ Turvis)

### 9.1 Byte-fidelity on outbound sign

`/turvis/v1/signatures/xmldsig` returns `signed_xml_b64`. edelivery sends these bytes AS-IS to the peer. No re-serialisation, no XML pretty-printing, no whitespace fiddling, no re-canonicalisation. Turvis's output IS the wire format.

Hard contract. Violations break signature verification on the peer side.

#### 9.1a Attachment and decrypt/verify byte contract

The legacy implementation signs and verifies the `cid:message` reference over the **decrypted, gzip-compressed payload** — never over the encrypted wire attachment. The contract keeps exactly that, so signed content stays verifiable across the four calls:

| Direction | Bytes the `cid:message` digest covers | Where they come from |
|---|---|---|
| Outbound sign | gzip(plaintext payload) | edelivery passes them as `attachment_bytes_b64`; the same bytes are the input of `/encryptions/xmlenc` |
| Outbound wire | `encrypted_payload_b64` (IV‖ciphertext‖tag) | encrypt response; **not** what is signed |
| Inbound decrypt | — | `decrypted_attachment_b64` = the gzip(plaintext) the sender signed |
| Inbound verify | gzip(plaintext payload) | edelivery passes `decrypted_attachment_b64` verbatim as `attachments[].bytes_b64` |

Rules:
- Decrypt MUST NOT alter any byte of an element covered by a `<ds:Reference>` (`eb:Messaging`, `env:Body`). In the AS4 profile the encrypted content is the attachment, so `decrypted_soap_xml_b64` equals the input envelope byte-for-byte (§13.0 compares "modulo whitespace" only to tolerate a different but equivalent encoder in `shadow`).
- edelivery passes the decrypt output to verify without re-encoding. A decrypt that changed signed content surfaces as `SIG_CRYPTO_FAIL`, never as silent acceptance.
- Plaintext profile: decrypt and encrypt pass bytes through unchanged; the same gzip convention applies, so one code path serves both profiles.

### 9.2 Policy enforcement on inbound verify

Every verification call carries `required_signed_xpaths` + `required_signed_cid_refs`. Turvis rejects with `SIG_POLICY_FAIL` if any required subtree is not in `SignedInfo.references`.

Policy table edelivery passes per call:

| Route (edelivery context) | required_signed_xpaths | required_signed_cid_refs |
|---|---|---|
| AS4 inbound `/services/msh` | `["//eb:Messaging", "//env:Body"]` | `["cid:message"]` |
| AS4 outbound sign (same subtrees) | same | same |

The policy table lives in edelivery config, not Turvis state.

### 9.3 edelivery's XML parsing surface after cutover

**Zero.** In `turvis` mode (and permanently once #312 removes the legacy path), edelivery does not parse untrusted XML at all:

- **Inbound**: edelivery MIME-splits the multipart (standard HTTP framework, non-XML), hands raw bytes to Turvis's decrypt + verify endpoints, reads routing/signer fields from Turvis's JSON responses, dispatches to handlers.
- **Outbound**: edelivery authors a SOAP envelope using string templating or DOM builder on **trusted** (edelivery-authored) input, calls Turvis to encrypt + sign, assembles multipart.

edelivery's parser is explicitly **not hardened** (consistent with #307): the design assumes untrusted input never reaches it. Hardening at ingress is A8 Ruuter perimeter checks + Turvis as the sole parser that touches peer-authored bytes. This is intentional — defense in depth comes from the Turvis boundary, not from a second hardened parser in edelivery.

Turvis's log hygiene, mesh-layer rejection of non-authorised clients, and egress restrictions are **Turvis's responsibility** — not duplicated here.

### 9.4 Signer trust model — chain of trust

In a 27-gate eFTI network with multiple CAs and platforms, per-peer **pinning** doesn't scale operationally — every peer cert rotation would require updating N-1 pinned lists, and adding a new gate would require updates in N-1 places. Turvis uses **chain of trust** with CA governance:

Trust decision on verify:
1. Signer cert MUST satisfy the party's trust mode (§7.2): equal the party's `cert_pem` (pinning, `allowed_chain_anchors` empty) or chain to one of its `allowed_chain_anchors` (chain of trust).
2. Signer cert's `verified_identity` (derived from the cert's Subject or SAN) MUST equal the `verified_identity` of some entry in the trust view's `parties[]`.
3. That party's `status` MUST be `ONLINE`.

Failure modes map to §5 reason codes:
- Chain fails → `SIGNER_UNTRUSTED`
- Chain OK but no `parties[]` entry matches the verified identity → `SIGNER_UNTRUSTED` (treated the same operationally — not authorised to sign)
- Chain OK, party matches, but `status != ONLINE` → `SIGNER_UNTRUSTED` with a detail line noting the status
- Signer identity doesn't match caller's `claimed_signer_id` (when provided) → `SIGNER_IDENTITY_MISMATCH`

**Pinning mode** (the Phase 1 default generated from the registry, §7.2): a party with no `allowed_chain_anchors` is verified against its exact `cert_pem`.
- Fleet migration path: all gates start pinned to their registered `eDeliveryCert`, then move party by party to chain-anchor mode as eFTI PKI governance ships. The mode is data, so flipping a party needs no code change.

**Current `singletonKeySelector` pattern in the legacy code**: incompatible with this model (hardwires a single pinned cert). Superseded by the per-party trust-view lookup; the legacy selector is deleted by #312 (§13.6).

---

## 10. Observability

### 10.1 `audit_ref` format

UUIDv4 generated per request on receive. Returned in response. Written to audit log + application logs.

### 10.2 Audit log entry schema

Each verification / signing / encryption / decryption decision writes exactly one audit entry:

```json
{
  "schema_version": "1.0",
  "audit_ref": "9f3e2a9b-...",
  "trace_id": "abc...",
  "timestamp": "2026-10-16T12:34:56.789Z",
  "endpoint": "/turvis/v1/verifications/xmldsig",
  "verdict": "accept",
  "reason_code": null,
  "signer_identity": "EU-FR",
  "signer_cert_ski": "a1b2c3...",
  "matched_signed_xpaths": ["//eb:Messaging", "//env:Body"],
  "matched_signed_cid_refs": ["cid:message"],
  "latency_ms": 42,
  "request_size_bytes": 11234
}
```

On reject: `verdict: "reject"`, `reason_code: "SIG_POLICY_FAIL"` etc. Other fields may be null.

**Audit log sink**: structured JSON lines to stdout. Downstream collector (fluent-bit / Loki) ships to central audit-log store. Retention matches edelivery's `audit_log` table retention (default 7 years for eFTI).

### 10.3 Metrics

OpenMetrics text format at `GET /metrics`. No auth on the metrics endpoint (loopback-only or NetworkPolicy-restricted).

Required metrics:

| Metric name | Type | Labels | Meaning |
|---|---|---|---|
| `turvis_requests_total` | counter | `endpoint`, `verdict` | Request count. |
| `turvis_request_duration_seconds` | histogram | `endpoint` | Latency. Buckets: 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5. |
| `turvis_rejections_total` | counter | `endpoint`, `reason_code` | Reject count. |
| `turvis_trust_store_entries` | gauge | — | Count of parties in the loaded trust view (§7.2). |
| `turvis_trust_store_last_reload_timestamp` | gauge | — | Unix timestamp of last successful reload. |
| `turvis_trust_store_load_failed_total` | counter | — | Count of trust store (re)load failures. Alert if increasing. |
| `turvis_trust_party_days_to_expiry` | gauge | `party_id` | Days until `valid_until` for each party in the loaded trust view. Alert on < 30. |
| `turvis_trust_generation_info` | gauge | `generation` | Always 1; label is the loaded trust-view generation (§7.4). Alert on more than one distinct value for > 5 min. |
| `turvis_own_cert_info` | gauge | `ski` | Always 1; SKI of the certificate behind `key_handle` (§7.4). |
| `turvis_own_cert_days_to_expiry` | gauge | — | Days until the own certificate expires. Alert on < 30. |
| `turvis_token_auth_after_mesh_total` | counter | — | Requests that authenticated via token when `AUTH_MODE=mtls-or-token`. **MUST be alerted on** — any non-zero value after mesh enrollment is a misconfiguration (SPEC §11.2). |
| `turvis_build_info` | gauge | `version`, `git_sha` | Static. |

Cardinality notes:
- `reason_code` label is bounded (see §5 enum).
- `endpoint` label is bounded (4 request endpoints + the audit-query endpoint).
- `party_id` label on `turvis_trust_party_days_to_expiry` is bounded by the trust-view party count (expected: 27 ± growth); `generation` by the number of live generations (≤ 2 outside a rolling deploy).
- Do NOT add peer `party_id` as a label on request counters — attackers could flood that cardinality.

### 10.4 Structured application logs

JSON lines to stdout. Fields: `timestamp`, `level`, `logger`, `message`, `trace_id`, `audit_ref` (if applicable). ECS-compatible for log aggregator parsing.

### 10.5 Audit log query API

For incident response, operators (edelivery operators, pentest firms, auditors) need to look up the full detail of a single verification / sign / encrypt / decrypt decision by its `audit_ref`.

**Endpoint**: `GET /turvis/v1/audit/{audit_ref}` — returns the full audit log entry (§10.2 schema) for the given `audit_ref`.

**Authorization**: separate authz domain from the four crypto endpoints. By default:
- mTLS mesh identity must present a configured `audit-reader` ServiceAccount (not edelivery's).
- Alternatively, when `AUTH_MODE=shared-token` or `mtls-or-token`: a separate `X-Audit-Reader-Token` header, distinct secret from `INTERNAL_SERVICE_TOKEN`.

**Response — found (200 OK)**: single audit log entry per §10.2 schema.

**Response — not found (404)**: audit_ref not known to this turvis instance. Note: `audit_ref` is a per-instance UUID; a multi-replica deploy means lookup requires either (a) cross-replica audit log aggregation into a shared store (preferred), or (b) the caller already knows which replica served the original request (via trace_id correlation).

**Retention of lookupability**: matches audit log retention (§10.2, default 7 years for eFTI). The query API MUST remain functional across the full retention window.

**Alternative pattern** (if a central audit-log store is deployed in Phase 2):
- Turvis writes to the central store (not just local stdout).
- Query API optionally proxies to the central store, OR this endpoint is removed in favour of direct store access by operators.
- Query API's existence in SPEC v1 is operator convenience; v2 may drop it in favour of central store access.

---

## 11. Auth between edelivery and Turvis

### 11.1 Post-A3 identity (future)

Turvis runs under its own ServiceAccount; the exact name is pinned by the A3 deployment team (`kemit-ee#309`). edelivery's egress NetworkPolicy allow-rule and the sec-side `.guard.yml` mTLS branch both key off that identity. No work on the dev-team side is blocked by this — Phase 1 token auth below covers the window until A3 ships.

### 11.2 Interim authentication (pre-A3)

A3 (strict mTLS mesh + NetworkPolicy) is a hard dependency on this build's final deployment, but ship-timeline-wise it may not be ready at first Turvis deploy. During the interim window Turvis MUST support a Turvis-dedicated token as a fallback authn mechanism.

- **Mechanism**: `X-Internal-Service-Token` request header. Shared-secret, compared constant-time via `MessageDigest.isEqual()` (JVM) or `subtle::ConstantTimeEq` (Rust).
- **Secret source**: environment variable **`TURVIS_INTERNAL_TOKEN`** (secret-ref). **Dedicated to Turvis — NOT the fleet's shared `INTERNAL_SERVICE_TOKEN`.** Turvis guards the private key; any internal service that knows the token has a sign/decrypt oracle. Separate secret = tight blast radius.
- **Enforcement toggle**: environment variable `AUTH_MODE` with values:
  - `mtls` (default, production target) — requires strict mTLS; rejects any request without valid client cert from the configured ServiceAccount.
  - `shared-token` (interim) — accepts requests that present a valid `X-Internal-Service-Token` header.
  - `mtls-or-token` (migration overlap) — accepts either. **Expires at `AUTH_MODE_TOKEN_EXPIRY` (RFC 3339 timestamp env var)**; after this timestamp, token-bearer requests reject with 401 regardless of `AUTH_MODE`. Required to prevent "temporary" overlap becoming permanent.
- **mTLS identity check**: ServiceAccount identity is in the client cert's **SAN (SubjectAlternativeName)** URI entries, typically formatted as `spiffe://cluster.local/ns/<namespace>/sa/<name>`. The guard matches against SAN entries, NOT the Subject DN (`client_subject_dn.indexOf(...)` would never match). See `.guard.yml` for the exact predicate.
- **Fail-closed**:
  - **Environment guard**: with `ENVIRONMENT` / `APP_ENV` / `DEPLOY_ENV` set to `prod` or `staging`, Turvis refuses to start when `AUTH_MODE=shared-token` (same shape as the §13.0 start-up guard against the mock). `shared-token` is the Phase-1 interim for compose dev; `mtls` is the target once #309 lands. `mtls-or-token` remains allowed in those environments only as the time-boxed migration overlap (`AUTH_MODE_TOKEN_EXPIRY` required). Neither mode replaces WS-Security or the trust-view match on the AS4 side. The Phase-1 Ruuter mock cannot enforce this (see below); a real Turvis must.
  - On startup: if `AUTH_MODE=shared-token` or `mtls-or-token` and `TURVIS_INTERNAL_TOKEN` is unset / equals a known-weak default / has length < 32 → Turvis refuses to boot.
  - Known-weak defaults (minimum): `""`, `"dev-token-change-me"`, `"changeme"`, `"turvis-dev"`.
  - This fail-closed check lives in the real Turvis startup code. The Phase 1 Ruuter mock **cannot** enforce it — that's a known gap documented here, not a mock bug.
- **Alert on token-bearer post-mesh**: when `AUTH_MODE=mtls-or-token` AND a request arrives via token, Turvis emits `turvis_token_auth_after_mesh_total` + a WARN log line. **Operators MUST configure a Prometheus alert on this counter** — WARN logs alone are easily missed. The counter should drop to zero within hours of flipping edelivery into mesh mode; non-zero after that = investigation.
- **Migration guidance**: deploy initially with `AUTH_MODE=mtls-or-token` + `AUTH_MODE_TOKEN_EXPIRY=` <date 2 weeks ahead>. After mesh enrollment is verified AND the alert metric is zero for 48h, flip to `AUTH_MODE=mtls` and remove the token secret from the deployment.
- **Deprecation**: `shared-token` and `mtls-or-token` are removed in v2 of this SPEC. Target removal: once A3 lands production-wide.

---

## 12. Interface stability + versioning

### 12.1 URL versioning

All endpoints under `/turvis/v1/`. The `v1` prefix is permanent for the lifetime of v1.

### 12.2 Breaking changes

A change is BREAKING if any of the following is true:
- A required request field is added
- A required request field is removed
- A response field is renamed or has its type changed
- A reason code is removed from the enum (adding new ones is non-breaking)
- A status code mapping changes

Breaking changes → new path prefix `/turvis/v2/`, with v1 remaining in parallel for at least one release cycle.

### 12.3 Non-breaking changes

- New reason code added (callers must treat unknown codes as reject)
- New optional request field added
- New response field added (callers must ignore unknown fields)
- Error message text changes
- Performance improvements

### 12.4 Deprecation policy

Deprecating a v1 path requires: 6-month advance notice + migration guide + telemetry showing <5% traffic on deprecated path before removal.

---

## 13. Edelivery-side integration contract

Everything the gate's `edelivery` service (kemit-ee#305 implementation) must do.

### 13.0 Transition model (legacy stays default until real Turvis verified)

The gate must keep working against the mock while real Turvis is still being built; this contract is **additive**, not a hard cutover.

**Default state after #305 lands**: legacy in-process crypto path is still the default. Turvis path is behind an environment-scoped feature flag:

```
TURVIS_INTEGRATION_MODE = legacy | shadow | turvis
  legacy  — in-process crypto only (default). Turvis not called.
  shadow  — in-process is authoritative; Turvis is called in parallel; results compared and logged.
  turvis  — Turvis is authoritative; legacy path is NOT invoked (code still present).
```

**Rollout sequence**:
1. **Dev / staging**: flip to `shadow`. Compare results across all in-flight messages. Discrepancies logged with `audit_ref` for triage.
2. **Shadow success criteria** (ALL must hold for ≥ 2 weeks before `turvis` promotion in that env):
   - Verify: `signer.verified_identity` matches between legacy and Turvis. `signature_digests` match byte-for-byte on sign. `ebms_routing` fields match.
   - Encrypt: NOT byte-equal (fresh AES key per call); shadow comparison here is "structure + round-trip-decrypts-cleanly," not byte equality.
   - Decrypt: `decrypted_attachment_b64` matches byte-for-byte. `decrypted_soap_xml_b64` matches modulo whitespace / namespace-prefix normalisation.
   - **Security decisions: zero unexplained discrepancies.** Accept/reject outcome, signer identity and signed-content coverage (`signer.verified_identity`, `matched_signed_*`, reject `reason_code`) differences are never absorbed by a percentage allowance; each one is resolved and retested before promotion.
   - Discrepancy threshold for all other differences: < 0.01% of messages, no new class of discrepancy in the trailing 7 days.
   - **Promotion volume and coverage** (decided 2026-10-22, #305b-2 review): over ≥ 14 days and ≥ 10 000 compared inbound messages: security discrepancies = 0; other discrepancies ≤ 0.01 % of compared; (errors + dropped) ≤ 1 % of attempted; and no error kind at 100 % for any peer. Counters: `edelivery_turvis_shadow_attempted_total_inbound` (every comparable message handed to the shadow), `..._compared_total_inbound`, `..._errors_total_<endpoint>_<kind>`, `..._dropped_total`. klite has no labels, so the per-peer condition is **log-based**: every skipped-comparison line carries `audit_ref` and `peer=<sender party id>` when the legacy path got far enough to know it (a peer that never gets past parsing shows as `peer=null`).
   - **Attachment compression**: the AS4 attachment must be gzip (`CompressionType` `application/gzip`); `turvis` mode treats an uncompressed attachment as `XML_PARSE_FAIL` (the legacy path accepts it, so shadow reports it as `outcome_accept_vs_reject`). Basis: all six Domibus PMode files of the EFTI4EU reference implementation set `compressPayloads="true"` on both legs, our outbound always compresses, and silent handling of an unknown `CompressionType` is forbidden by the CFR. AS4 2.0 only recommends gzip (1.14 required it). AS4 2.0 has `EBMS:0303 DecompressionFailure`; we answer `XML_PARSE_FAIL` for now (revisit in the cutover code catalogue).
3. **Dev / staging**: flip to `turvis`. Legacy code remains in place but is NOT called in the hot path. Monitor for 1-2 weeks.
4. **Prod**: repeat 1-3.
5. **Only AFTER prod has run on `turvis` for ≥ 30 days and the rollback window is formally closed** may legacy removal start (§13.6, kemit-ee#312, a dedicated PR). Legacy code deletion is **not** at #305 merge time and **not** part of #308.

**Ownership and gates** (agreed on PR #311; replaces the earlier 7d/3d/30d sketch in #308):

| Item | Owner |
|---|---|
| Integration code, `legacy`/`shadow`/`turvis` flag | #305 |
| Mock and contract checks | #306 |
| Real-service interop, shadow comparison, promotion, observation, **decision to close the rollback window** | #308 (named promotion owner + one independent reviewer record each decision with evidence) |
| Mesh and NetworkPolicy enforcement | #309, verified through #307 |
| Final code, key-access, flag and fallback removal | #312 |
| Pre-removal audit (GO / NO-GO) | #313 |

- **Rollback** is a configuration flip (`turvis` → `shadow` → `legacy`) and stays available from the first prod `turvis` flip until the rollback window is closed. The window ends only by a recorded #308 decision; #312 then removes the flag.
- **Hard gate**: no removal work, PR or deployment starts until #313 is closed as completed with an explicit GO and the agreed CI suite is green for the recorded baseline SHA. Any relevant change invalidates the GO.
- Shadow comparison is run against the **real** Turvis versus the legacy path, never against the mock.

**Shadow-mode invariants** (assert each with a test): the Turvis call in `shadow` is bounded and isolated from the AS4 critical path (a slow, failing or crashing call adds no latency to and cannot fail the authoritative legacy path); discrepancies are counted by `endpoint` and `kind` so the promotion criteria above are queryable rather than log-read; verified routing and sender binding come from the verify response only (§13.9).

**Start-up guard** (edelivery, not the mock): refuse to start when `TURVIS_URL` resolves to the `turvis-mock` host and `ENVIRONMENT`/`APP_ENV`/`DEPLOY_ENV` is `prod` or `staging`; the mock is excluded from production deployment manifests.

**Mock-must-not-be-laxer-than-real rule**: anything the real Turvis rejects must be reachable via `X-Mock-Verdict` on the Phase 1 mock. Specifically — plaintext inbound (no `<xenc:*>`) in a context where encryption is required, identity mismatch, missing signature, bad algorithm. The mock's `/metadata` route (optional, future) can declare which reject reasons are reachable; a CI check compares against §5.

**Shared-change backward compatibility**: any field added to `MessageHeader` or any shape change that is shared between the legacy path and the Turvis path must be backward-compatible — both paths must work in `shadow` mode. Tests cover BOTH paths while shadow is active.

**Contract-drift CI gate (required, not recommended)**:
- `dsl-lint --audit` on `DSL/Ruuter-turvis-mock/` → 0 errors, 0 warnings
- `dsl-test` scenarios (`DSL-turvis-mock-tests/`) → 100% pass
- Schema cross-check (`scripts/validate-turvis-contract.py`, unit-tested by `tests/turvis/test_validate_turvis_contract.py`): the fields required by SPEC §4 and the DSL `allowlist:` are the same set with matching types, the accept-response fields match the DSL `returns:`, and every reason code in §5 is a reject step with the §5 status and has a mock scenario

CI runs on every PR touching `docs/specs/turvis/` AND on every PR touching `edelivery/` code (because edelivery depends on this contract).

### 13.1 Request construction

For each turvis call, edelivery:
1. Generates / propagates `trace_id` (W3C traceparent)
2. Builds request body per §4 schema
3. Base64-encodes raw bytes (don't re-serialise XML; use the as-received bytes)
4. Sends `POST` with `Content-Type: application/json`

### 13.2 Response handling

On 200 OK:
- Parse response body
- Log `audit_ref` cross-ref
- Metrics increment: `edelivery_turvis_calls_total{endpoint=..., verdict="accept"}`
- Proceed with returned data

On 422 (reject):
- Parse problem+json body
- Log `audit_ref` cross-ref
- Metrics increment: `edelivery_turvis_calls_total{endpoint=..., reason_code=...}`
- Map `reason_code` to a specific SOAP fault code per the table below

On 500 / 503 / network error:
- Log + metric
- Inbound path: return 503 Service Unavailable to the peer AS4 caller (peer will retry)
- Outbound path: see §13.3a (async + retry policy)

#### 13.2a `reason_code` → SOAP fault / internal exception mapping

For inbound AS4 messages, Turvis reject responses must be translated to AS4 (ebMS 3.0) faults the peer can interpret. For admin/internal paths, map to specific exception classes.

| reason_code | AS4 fault code (ebMS 3) | Short description | Internal exception |
|---|---|---|---|
| `XML_PARSE_FAIL` | `EBMS:0004` InvalidHeader | Malformed XML | `MalformedEnvelopeException` |
| `XML_ATTACK_DETECTED` | `EBMS:0004` InvalidHeader | Parser safety limit hit | `HostileInputException` |
| `SIG_MISSING` | `EBMS:0101` SignatureFailure | Required signature absent | `SignatureMissingException` |
| `SIG_CRYPTO_FAIL` | `EBMS:0101` SignatureFailure | Signature invalid | `SignatureInvalidException` |
| `SIG_POLICY_FAIL` | `EBMS:0101` SignatureFailure | Required subtree not signed | `SignaturePolicyException` |
| `SIG_ALGO_UNSUPPORTED` | `EBMS:0101` SignatureFailure | Non-allowed algorithm | `SignatureAlgoException` |
| `SIGNER_UNTRUSTED` | `EBMS:0101` SignatureFailure | Chain or authorisation failed | `SignerUntrustedException` |
| `SIGNER_IDENTITY_MISMATCH` | `EBMS:0101` SignatureFailure | Cert identity ≠ claimed | `SignerIdentityMismatchException` |
| `SIG_BUILD_FAIL` | (outbound-only) | Internal signing error | `OutboundSignBuildException` |
| `KEY_HANDLE_UNKNOWN` | (outbound-only) | Internal — gate's key not loaded | `KeyHandleUnknownException` |
| `KEY_UNAVAILABLE` | (outbound-only, transient) | KMS unreachable; retryable | `KeyUnavailableException` |
| `WRAPPED_KEY_MALFORMED` | `EBMS:0102` DecryptionFailure | Bad wrapped key | `WrappedKeyException` |
| `SKI_MISMATCH` | `EBMS:0102` DecryptionFailure | Wrong recipient key | `RecipientMismatchException` |
| `AES_UNWRAP_FAIL` | `EBMS:0102` DecryptionFailure | Key unwrap failed | `KeyUnwrapException` |
| `PAYLOAD_DECRYPT_FAIL` | `EBMS:0102` DecryptionFailure | Tag mismatch, likely tampered | `PayloadDecryptException` |
| `RECEIVER_UNKNOWN` | (outbound-only, business) | Peer not in trust store | `ReceiverUnknownException` |
| `RECEIVER_CERT_EXPIRED` | (outbound-only, business) | Peer cert expired | `ReceiverCertExpiredException` |
| `RECEIVER_CERT_UNTRUSTED` | (outbound-only, business) | Peer cert chain fail | `ReceiverCertUntrustedException` |
| `PAYLOAD_TOO_LARGE` | `EBMS:0003` ValueInconsistent | Oversize payload | `PayloadTooLargeException` |
| `UNKNOWN_RETURN_FIELD` | (programmer error) | edelivery sent bad `return_fields` | `UnknownReturnFieldException` — ALERT, this is a code bug |
| `ALGO_UNSUPPORTED` | `EBMS:0003` ValueInconsistent | Algorithm not allowed | `AlgoUnsupportedException` |
| `TRUST_STORE_UNAVAILABLE` | (transient) | File reload failed; retryable | `TrustStoreUnavailableException` |
| `TURVIS_INTERNAL` | HTTP 503 to the peer, no ebMS fault (inbound; not a peer error, §13.2) / exception (outbound) | Internal Turvis bug | `TurvisInternalException` — ALERT |

### 13.3 Timeout + retry

- Per-call HTTP timeout: 10 seconds (well above p99 budget of 150ms).
- **Retried (max 2 retries, backoff 100 ms then 300 ms; then fatal for the message):** a 503 carrying `KEY_UNAVAILABLE` or `TRUST_STORE_UNAVAILABLE`, and, for the idempotent endpoints (`verifications`, `decryptions`, `signatures`; §4.1/§4.2/§4.4 "same request → identical response"), a network error or timeout.
- **`/encryptions/xmlenc` in `wss-encrypted` profile** (not byte-idempotent, fresh AES key per call): retried **only** on a 503 reject with a reason code. A reject never carries ciphertext or a wrapped key, so a retry cannot leave two usable results. Network errors, timeouts and any response without a reason code are **not** retried, because the first call may have produced a key; the message goes to `OUTBOUND_FAILED` (§13.3a). `plaintext` profile has no such restriction.
- **Never retried:** non-transient 4xx (`SIG_POLICY_FAIL`, `UNKNOWN_RETURN_FIELD`, …) and `TURVIS_INTERNAL` (500) — a retry only adds duplicate audit entries or masks a bug.
- Retries run inside the asynchronous outbound job (§13.3a); they never block an inbound peer response beyond the inbound path rules in §13.2.
- "Retry safe" in §5 therefore means *transient*, not "retry is unconditionally allowed"; this section is the only normative retry policy.

#### 13.3a Async outbound failure handling

Outbound AS4 messages are produced asynchronously: edelivery's handler returns before Turvis's sign/encrypt completes. There is no caller to return 503 to. On a non-transient failure during outbound:

1. Mark the message `OUTBOUND_FAILED` with the Turvis `audit_ref` + `reason_code`.
2. Emit an alert metric: `edelivery_outbound_turvis_failures_total{reason_code=...}`.
3. The failed message is visible in the admin UI; operators can retry manually after the underlying cause is fixed (e.g. trust store updated, Turvis bug patched).
4. **Do not** silently drop the message. **Do not** retry an encrypt automatically (fresh key per attempt accumulates audit noise + confuses downstream retry semantics).

### 13.4 Byte-fidelity preservation

See §9.1 — hard contract.

### 13.5 Policy declaration

For every verification call, edelivery declares `required_signed_xpaths` and `required_signed_cid_refs` per the policy table in §9.2. Policy table lives in edelivery config, not turvis state.

### 13.6 Code deletions on legacy retirement (kemit-ee#312)

These deletions are made **only** in the dedicated removal PR tracked by #312, after §13.0 step 5 and a #313 GO. #305 and #308 delete nothing. After retirement:

**Private key + crypto code:**
- `code/edelivery/src/edelivery/KeyManager.ownPrivateKey` field: deleted
- Private-key secret volume mount (`/app/certs/own.p12`) and `KEYSTORE_PASSWORD`: removed from edelivery container and deployment guidance
- `TURVIS_INTEGRATION_MODE` and shadow/fallback branches: removed; no supported way to enable legacy mode remains in current configuration or runbooks (historical ADRs stay as history)
- `code/edelivery/src/edelivery/SignatureValidator.kt`: deleted
- `code/edelivery/src/edelivery/Xml.kt::canonicalXml()`: deleted
- `code/edelivery/src/edelivery/EDeliveryMessageGenerator`: signing + encryption methods deleted
- `code/edelivery/src/edelivery/EDeliveryRoutes.decryptPayload`: body deleted (HTTP call to `/turvis/v1/decryptions/xmlenc` remains)

**Data-model cleanup (edelivery stops parsing SOAP envelopes):**
- Any fields on `MessageHeader` referring to signature or encryption state — deleted. If no non-crypto field on `MessageHeader` remains, delete the whole data class — routing fields now come from Turvis's `ebms_routing` response field (§4.1, §4.4), not from a locally-parsed `<eb:Messaging>` DOM.
- `WssSecurityHeader` / `WssSignatureInfo` / `WssEncryptedKey` data classes: deleted. Turvis takes raw SOAP bytes and returns verdict JSON — no edelivery model for `<wsse:Security>`.
- `CertificateChainValidator`, `TrustStoreLoader`, or any cert-handling code: deleted. Signer identity comes to edelivery as `signer.cert_subject_dn` + `signer.cert_ski` fields in Turvis's verify response.
- Any `XmlParser`/`DomBuilder` configured for inbound untrusted XML: deleted or restricted to outbound/edelivery-authored content only (per §9.3).

### 13.7 Record-level fields for audit / incident response

For audit, non-repudiation receipts, and incident lookup, edelivery stores a small set of per-message fields **alongside** the message record (new columns on the existing message/audit table, or a side table — edelivery's call). These are record metadata; no `MessageHeader` data class is required.

| Field | Source | Purpose |
|---|---|---|
| `turvis_audit_ref_verify` | Turvis verify response `audit_ref` | Lookup via `GET /turvis/v1/audit/{ref}` on incident |
| `turvis_audit_ref_decrypt` | Turvis decrypt response `audit_ref` | Same |
| `turvis_audit_ref_sign` | Turvis sign response `audit_ref` | Same (outbound) |
| `turvis_audit_ref_encrypt` | Turvis encrypt response `audit_ref` | Same (outbound) |
| `signer_identity` | Turvis verify response `signer.verified_identity` | Audit log + business authorisation |
| `signer_cert_ski` | Turvis verify response `signer.cert_ski` | Operator-facing provenance; survives cert rotation |
| `message_id`, `conversation_id`, `ref_to_message_id`, `from_party_id`, `to_party_id`, `service`, `action` | Turvis's `ebms_routing` (verify response; the decrypt copy is diagnostic only) | Routing + correlation; replaces any edelivery-side ebMS-header parse |

edelivery's implementation (#305d-2) is one structured log line per message (`turvis_audit direction=inbound|outbound`, ids only, values outside a narrow character set written as `?`), because edelivery has no message table; a durable table remains open.

For AS4 non-repudiation receipts (NRR), edelivery takes `signature_digests` from Turvis's verify response (§4.1) and places each reference in the receipt XML it builds as the verbatim `reference_xml_b64` element (§4.1), not rebuilt from the digest values; it then calls `/turvis/v1/signatures/xmldsig` to sign the receipt. edelivery never computes or verifies any digest.

### 13.8 Payload size budget

A 50 MiB payload becomes ~67 MB when base64-encoded for JSON transport. edelivery must raise its own body-size cap to accommodate; Ruuter's 16 MiB default + edelivery's 10 MB default BOTH cap below the effective max. Target effective max for Phase 1: **40 MiB plaintext → ~54 MB base64 JSON**. If a larger message arrives from a peer, Ruuter should reject at the perimeter with HTTP 413 before Turvis is called.

edelivery's side of the budget is `TURVIS_MAX_PAYLOAD_MIB` (default 40): the decompressed inbound payload and the outbound payload are refused above it, and a Turvis answer is read only up to a bound derived from it (a larger answer, or one that repeats a JSON key, is a protocol error). The HTTP body caps (`BODY_LIMIT_MD`, the Ruuter perimeter) are deployment settings that must be raised to match, but not above `payload * 4/3` plus the envelope, and the edelivery memory limit must allow several ~54 MB copies of a maximum payload. In shadow mode a legacy size refusal against a Turvis accept of a larger payload is the non-security discrepancy `size_budget`.

Note: this cap is driven by HTTP-body-handling costs on both sides, not by crypto limits. If a larger payload is operationally needed, consider streaming-mode endpoints as a future addition (not in this contract).

### 13.9 Authoritative sender identity

The authoritative sender for business authorisation is `signer.verified_identity` from Turvis's verify response. **NOT** `ebms_routing.from_party_id` (which is from the envelope — content, not cryptographic attestation).

Business paths (party permission lookup, authz checks) use `signer.verified_identity`. If the two disagree, Turvis has already rejected with `SIGNER_IDENTITY_MISMATCH` when `claimed_signer_id` was passed; without `claimed_signer_id` they're identical by construction (Turvis extracts both from the envelope but the signature attestation wins).

### 13.10 `claimed_signer_id` + party_id format

The SPEC §4.1 regex `^[A-Z]{2}-[A-Z0-9]{2,8}$` is the FORMAL gate-identity format for cross-border AS4 (`EU-FR`, `EU-EE`). Dev/test environments use party IDs that don't fit (`mock`, `mock-edelivery`, `pikker`). The regex is therefore **advisory, not enforced** — Turvis accepts any non-empty string ≤ 64 chars matching `^[a-zA-Z0-9][a-zA-Z0-9_\-]{0,63}$`. Production deploys may enforce the stricter pattern via a separate CI gate on the `registry/` ids.

### 13.11 Dev loopback replacement (no self-loop)

The legacy `acceptsReceiver` pattern (EU-MOCK sharing EU-EE's cert for one-container self-testing) is **removed** at cutover. Replacement patterns:

- **Preferred**: dev gate-to-gate testing uses `multiple-gates.sh` (estlandia/latveria/lithonia) in docker-compose. Each gate runs its own edelivery, pointing at a shared `turvis-mock` compose service. Mock pass-through semantics make the three mock gates self-consistent without needing cert-reuse.
- **Alternative**: `turvis-mock` dev-mode toggle (not in this SPEC; separate from production turvis-mock behaviour): `TURVIS_MOCK_PERMISSIVE_IDENTITY=true` → accepts any `claimed_signer_id` without trust-store lookup. Scoped to `turvis-mock` only; the real Turvis never has this flag.
- **Dropped**: `acceptsReceiver` + `isReceiverAccepted` methods in edelivery; EU-MOCK party entry in registry. Removed by #312 (§13.6).

---

## 14. Example requests + responses

### 14.1 Verify — accept

**Request**:

```http
POST /turvis/v1/verifications/xmldsig HTTP/1.1
Host: turvis.turvis.svc.cluster.local:8443
Content-Type: application/json
X-Request-Id: 9f3e2a9b-...

{
  "soap_xml_b64": "PGVudjpFbnZl...",
  "attachments": [
    {"cid": "cid:message", "bytes_b64": "PEZUSTAwNFVwbG9hZC..."}
  ],
  "required_signed_xpaths": ["//eb:Messaging", "//env:Body"],
  "required_signed_cid_refs": ["cid:message"],
  "return_fields": ["signer", "matched_signed_xpaths", "matched_signed_cid_refs", "signature_digests", "ebms_routing"],
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

Note: `claimed_signer_id` is optional (omitted here — Turvis derives from `<eb:From>/<eb:PartyId>`).

**Response**:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "verdict": "accept",
  "audit_ref": "9f3e2a9b-1234-5678-90ab-cdef12345678",
  "signer": {
    "cert_subject_dn": "CN=EU-FR-gate,O=DGITM",
    "cert_ski": "a1b2c3d4e5f6...",
    "cert_serial": "12345678901234567890",
    "verified_identity": "EU-FR"
  },
  "matched_signed_xpaths": ["//eb:Messaging", "//env:Body"],
  "matched_signed_cid_refs": ["cid:message"],
  "signature_digests": [
    {"reference_uri": "#MSG-ID", "subtree_xpath": "//eb:Messaging", "digest_b64": "DIjgcEkiAb...=", "algorithm": "SHA-256", "transforms": ["http://www.w3.org/2001/10/xml-exc-c14n#"], "reference_xml_b64": "PGRzOlJlZmVyZW5jZSB4bWxu..."},
    {"reference_uri": "#BODY-ID", "subtree_xpath": "//env:Body", "digest_b64": "9m0VSvOx...=", "algorithm": "SHA-256", "transforms": ["http://www.w3.org/2001/10/xml-exc-c14n#"], "reference_xml_b64": "PGRzOlJlZmVyZW5jZSB4bWxu..."},
    {"reference_uri": "cid:message", "digest_b64": "...", "algorithm": "SHA-256", "transforms": ["http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Signature-Transform"], "reference_xml_b64": "PGRzOlJlZmVyZW5jZSB4bWxu..."}
  ],
  "ebms_routing": {
    "message_id": "fr-gate-20261016-00001@dgitm.fr",
    "conversation_id": "fti-conv-00001",
    "ref_to_message_id": "",
    "from_party_id": "EU-FR",
    "to_party_id": "EU-EE",
    "service": "FTI004",
    "action": "FTI004Upload"
  }
}
```

### 14.2 Verify — reject (policy fail)

**Request**: same as above but `soap_xml_b64` signed only over `<ds:KeyInfo>` not over the required subtrees.

**Response**:

```http
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/problem+json

{
  "type": "https://efti.example/problem/turvis-verify-xmldsig-reject",
  "title": "Signature policy failed",
  "status": 422,
  "verdict": "reject",
  "reason_code": "SIG_POLICY_FAIL",
  "detail": "Required subtree '//env:Body' not in SignedInfo.references",
  "audit_ref": "9f3e2a9c-..."
}
```

### 14.3 Sign — accept

**Request**:

```http
POST /turvis/v1/signatures/xmldsig HTTP/1.1
Content-Type: application/json

{
  "soap_xml_b64": "PGVudjpFbnZlbG9wZS4uLg==",
  "sign_xpaths": ["//eb:Messaging", "//env:Body"],
  "sign_cid_refs": ["cid:message"],
  "attachment_bytes_b64": [
    {"cid": "cid:message", "bytes_b64": "..."}
  ],
  "key_handle": "EU-EE",
  "return_fields": ["signatures"],
  "trace_id": "..."
}
```

**Response**:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "signed_xml_b64": "PGVudjpFbnZlbG9wZSB4bWxuczp...",
  "audit_ref": "...",
  "signatures": [
    {"reference_uri": "#MSG-ID", "subtree_xpath": "//eb:Messaging", "digest_b64": "...", "algorithm": "SHA-256", "transforms": ["http://www.w3.org/2001/10/xml-exc-c14n#"]},
    {"reference_uri": "#BODY-ID", "subtree_xpath": "//env:Body", "digest_b64": "...", "algorithm": "SHA-256", "transforms": ["http://www.w3.org/2001/10/xml-exc-c14n#"]},
    {"reference_uri": "cid:message", "digest_b64": "...", "algorithm": "SHA-256", "transforms": ["http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Signature-Transform"]}
  ]
}
```

### 14.4 Encrypt — accept (wss-encrypted mode)

**Request**:

```http
POST /turvis/v1/encryptions/xmlenc HTTP/1.1
Content-Type: application/json

{
  "plaintext_bytes_b64": "H4sIAAAAAAAA...",
  "receiver_party_id": "EU-FR",
  "profile": "wss-encrypted",
  "return_fields": ["wrapped_key_xml", "encrypted_data_xml", "wrapped_key_b64"],
  "trace_id": "..."
}
```

**Response**:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "encrypted_payload_b64": "AAAAAAAAAAAAAAAA...",
  "audit_ref": "...",
  "wrapped_key_b64": "aGVsbG8gd29ybGQ=",
  "wrapped_key_xml": "<xenc:EncryptedKey xmlns:xenc=\"...\" Id=\"EK-ID\">...</xenc:EncryptedKey>",
  "encrypted_data_xml": "<xenc:EncryptedData xmlns:xenc=\"...\">...</xenc:EncryptedData>"
}
```

### 14.4a Encrypt — accept (plaintext mode)

Same request shape with `profile: "plaintext"` and (optionally) empty `return_fields: []`:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "encrypted_payload_b64": "H4sIAAAAAAAA...",  // identical to request's plaintext_bytes_b64
  "audit_ref": "..."
}
```

edelivery's SOAP splicing is a no-op when the XML fragments are absent/empty.

### 14.5 Decrypt — accept

**Request**:

```http
POST /turvis/v1/decryptions/xmlenc HTTP/1.1
Content-Type: application/json

{
  "soap_xml_b64": "PGVudjpFbnZlbG9wZS4uLg==",
  "encrypted_attachment_b64": "AAAAAAAAAAAAAAAA...",
  "key_handle": "EU-EE",
  "return_fields": ["decrypted_soap_xml_b64", "decrypted_attachment_b64"],
  "trace_id": "..."
}
```

**Response**:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "audit_ref": "...",
  "decrypted_soap_xml_b64": "PGVudjpFbnZl...",
  "decrypted_attachment_b64": "H4sIAAAAAAAA..."
}
```

---

## 15. Glossary

- **AS4**: OASIS ebMS 3.0 Advanced AS4 messaging profile. Specifies SOAP-with-attachments transport for business messages between e-delivery gateways.
- **ebMS 3.0**: OASIS Electronic Business Messaging Services version 3.0. SOAP-based messaging specification.
- **edelivery**: the Kotlin service in the eFTI gate that handles AS4 inbound/outbound traffic.
- **exc-c14n**: W3C Exclusive XML Canonicalisation 1.0. Tree-based deterministic serialisation algorithm for XMLDSig.
- **FTI004 / FTI007 / etc.**: eFTI message schemas defining the business payload structure.
- **KMS**: Key Management Service. Cloud / HSM-backed private key holder (AWS KMS, GCP KMS, HashiCorp Vault, etc.).
- **klite**: the Kotlin HTTP framework the gate's JVM services (`edelivery`, `xml-mapper`) are built on. It is a library, not a component of this contract.
- **mTLS**: mutual TLS. Client + server both present + verify certificates.
- **OWASP XXE Prevention**: canonical hardening guide for XML parsers against external entity attacks.
- **RFC 7807**: HTTP problem+json error response format.
- **SKI**: X.509 Subject Key Identifier. Hash of the public key, usable as a stable identity reference.
- **SwA**: SOAP with Attachments. OASIS profile for binding non-XML content to SOAP messages via MIME multipart.
- **Trust store**: the least-privilege trust view Turvis loads (peer identity, status, certificate, trust anchors). Generated from `registry/` (§7.2); never edited by hand.
- **WS-Encryption**: OASIS Web Services Encryption. XML syntax for RSA-OAEP key wrapping + AES-GCM payload encryption of SOAP body / attachment.
- **XMLDSig**: W3C XML Signature. XML syntax for digital signatures over XML content.

---

## 16. Non-goals

Explicitly excluded from this build. If any of these later come into scope, that is a new spec version.

- **Multi-tenancy**: Turvis serves one gate (one key handle). Multi-key support is a v2 feature.
- **Peer discovery**: Turvis receives peer identities via trust store input only. It does not probe or crawl.
- **Business workflow**: no FTI004/FTI007 awareness. Payload is opaque bytes to Turvis.
- **UI**: Turvis has no human-facing interface.
- **Replay protection**: Turvis is stateless w.r.t. message history. Replay detection stays with edelivery (K5 state machine territory).
- **Rate limiting**: at mesh layer, not application layer.
- **Request logging** of raw payload: explicitly forbidden (§9.3).
- **PKCS#11 / HSM direct access**: use KMS abstraction instead.

---

## Appendix A — Change log

| Date | Change | Reason |
|---|---|---|
| 2026-10-07 | Initial draft (04-INTERFACES.md) | A9 sub-project kickoff |
| 2026-10-15 | Endpoint rename: `/sec/<verb>` → `/sec/v1/<resources>` | REST compliance per CFR api_standardisation.md |
| 2026-10-15 | Klite → edelivery terminology | Partner request (angryziber on kemit-ee#304) |
| 2026-10-15 | Shadow-mode comparison target clarified | sviljus Q19 — compare vs legacy in-process, not vs mock |
| 2026-10-16 | Consolidated into single build spec | For LLM-consumable buildability |
| 2026-10-16 | Added interim auth section (now §11.2) + audit query API (§10.5) | sviljus Q12 (interim auth) + Q14 (audit_ref query API) |
| 2026-10-16 | Ruuter DSL contracts become the authoritative source of REST shapes; JSON Schemas in §4 kept as companion documentation | User decision: unblock dev team via turvis-mock DSL now, lock contract in fleet-native format for future real Turvis |
| 2026-10-16 | Nested URL redesign: `/sec/v1/<op>` → `/sec/v1/<op>/<mechanism>` (`/verifications/xmldsig`, `/signatures/xmldsig`, `/encryptions/xmlenc`, `/decryptions/xmlenc`) | Future JWS/JWE/COSE siblings drop in without a `/v2/` bump; see §3.1 |
| 2026-10-16 | Trimmed to dev-team contract scope: removed §1.5 language choice, §6.3 RNG, §7 internals, §8.2–§8.5 SLOs, §9.3–§9.5 log/mesh/egress internals, §11.2–§11.4 K8s YAMLs, §12 testing, §13 deployment. Items replaced by "sec service's responsibility" pointers | User direction: don't document sec-service internals in the dev-partner contract |
| 2026-10-16 | Component renamed: "sec service" / `sec-service` / `/sec/v1/` → "Turvis" / `turvis` / `/turvis/v1/`. Bürostack component naming (fleet-consistent with Ruuter / Resql / TIM). Problem+json type URIs, env var `SEC_SERVICE_URL`→`TURVIS_URL`, reason-code `SEC_SERVICE_INTERNAL`→`TURVIS_INTERNAL`, K8s identifiers all follow | User decision: adopt a new Bürostack component for the sec-service role, named Turvis |
| 2026-10-16 | Scope framing: Turvis positioned as a Bürostack security-operations component (crypto + validation + hardened parse) rather than a crypto-only sidecar. Phase 1 contract remains the four cryptography endpoints; `/validations/*` + `/parsings/*` listed as planned follow-ups (not in this contract) to anchor the §3.1 extensibility story | User direction: "Turvis doesn't just do crypto, but also validates XML and other payloads — fully focused on sec-thirsty jobs" |
| 2026-10-16 | §13.6 renamed "Private-key removal" → "Code deletions on cutover" and extended to list `MessageHeader` cleanup (sig/encryption fields removed; MessageHeader stays ebMS routing only) + WSS data-class deletions + cert-handling code deletions. Added §13.7 "Record-level fields for audit / incident response" (per-message `turvis_audit_ref_*` + `signer_identity` + `signer_cert_ski` fields stored alongside the message, NOT inside MessageHeader) | Dev-team question: does new edelivery's `MessageHeader` still need encryption + signature fields? Answer: no — call out the full data-model cleanup in SPEC |
| 2026-10-16 | **Pattern B — `return_fields` on every request.** New §4.0 introduces per-request response-field projection: caller declares what it wants back; unknown values reject with `UNKNOWN_RETURN_FIELD` (new reason code). Projectable enum per endpoint (§4.1–§4.4). Phase 1 mock validates presence but returns full bundle; real Turvis enforces field-level filtering + enables future caller-capability policy | User direction: Pattern B wins over Pattern A — response-size, forward-compat, response-stuffing defense, and declaration-level security rules in future outweigh the extra mechanics |
| 2026-10-16 | **Verify (§4.1): `claimed_signer_id` moved required → optional**, added `ebms_routing` + `signature_digests` as projectable fields. **Decrypt (§4.4): request shape changed to `soap_xml_b64` + `encrypted_attachment_b64`** (Turvis extracts `<xenc:EncryptedKey>` wrapped key + `<wsse:KeyIdentifier>` SKI internally — edelivery never parses crypto elements); response is `decrypted_soap_xml_b64` + `decrypted_attachment_b64` + `ebms_routing`. **Encrypt (§4.3): added `profile: "wss-encrypted" \| "plaintext"`** so edelivery has one code path for both modes; plaintext mode returns empty XML fragments + input passthrough. **Sign (§4.2): `signatures` moved from always-returned to projectable** (NRR callers request it; non-NRR callers can skip) | sviljus PR#311 review items 5-9. The verify/decrypt pair now ensures edelivery NEVER parses the SOAP envelope — all routing/signer/digest fields come from Turvis's JSON responses. Signature digests are in-contract now (not deferred to NRR epic) so edelivery's receipt code is stable across Phase 1 → Phase 2 |
| 2026-10-16 | §9.3 added: "edelivery's XML parsing surface after cutover" = **zero**. §2.2 inbound flow diagram rewritten to show the no-parse pattern. §13.6 data-model cleanup made stricter (`MessageHeader` class deletable if no non-crypto field remains) and §13.7 adds `ebms_routing` fields to the record-level columns list | Aligns with Pattern B + new verify/decrypt shapes; resolves sviljus's "hardened vs unhardened parser contradiction with #307" concern |
| 2026-10-16 | DSL correctness fixes, round 1 (cba5173) | sviljus PR#311 review items 1-4 addressed in `cba5173`: `internal: true` → `false`; `${crypto.randomUUID()}` → Math.random UUIDv4; multi-line `${…}` conditions folded to single line; `&reject_template` anchor inlined. `.guard.yml` still had a `dsl-lint` error that cba5173 did not resolve. |
| 2026-10-16 | DSL correctness fixes, round 2 (f470cd3, authored by sviljus) | sviljus directly pushed `f470cd3`: wrapped the guard's `switch:` under a `check_auth:` named step so dsl-lint parses it; added `wrapper: false` to every returning step in the 4 routes (otherwise responses come back wrapped as `{"response": {...}}`); added a comment on `reject_unknown_return_field` noting it's only reachable via `X-Mock-Verdict`. `dsl-lint --audit` now reports 0 errors. |
| 2026-10-16 | sviljus follow-up items (SPEC §4.0 caveat + changelog attribution + dsl-test scenarios + minor lint) | Addressed in this commit. §4.0 caveat now states `UNKNOWN_RETURN_FIELD` on the mock is reachable only via `X-Mock-Verdict`; changelog corrected to attribute the guard fix to sviljus's `f470cd3`; dsl-test scenarios added under `dsl-contracts/tests/`; em-dash fix at `verifications/xmldsig.yml`. |
| 2026-10-16 | **Shared trust store** adopted (SPEC §7.2) | User decision: both Turvis AND edelivery read the same `parties.yml` file as single source of truth. edelivery reads only the routing slice; Turvis reads only the trust slice. Zero drift by construction. Private key stays Turvis-only; TLS material stays with edelivery/mesh. Signed-manifest integrity verification by Turvis at load. Replaces an earlier proposal of a `GET /turvis/v1/trust/parties` API + runtime consistency check from edelivery. |
| 2026-10-16 | **Transition model §13.0**: legacy stays default, flag-gated migration via `TURVIS_INTEGRATION_MODE=legacy\|shadow\|turvis`; code deletions (§13.6) delayed to post-cutover-30-days, not at #305/#308 merge | User + sviljus review: gate must keep working against the mock while real Turvis is being built. Required (not recommended) CI contract-drift gate added. Mock-must-not-be-laxer-than-real rule. Shadow-mode criteria defined (byte-equal where deterministic, structural-equal where not). |
| 2026-10-16 | **Chain-of-trust signer model** (§9.4) replaces per-peer pinning | 27-gate network scale: pinning requires N-1 updates per cert rotation. Trust store now holds roots + per-party authorisation entries; `pinned_cert_pem` per-entry is optional interim. Current `singletonKeySelector` legacy pattern explicitly incompatible — replaced wholesale during cutover. |
| 2026-10-16 | **XSD ownership clarified**: FTI004/FTI007 stays in `xml-mapper` permanently; Turvis's future `/validations/xmlschema` takes caller-supplied schemas (hash-cached), never eFTI-specific | Resolves sviljus's §1.4 contradiction. Turvis provides hardened parse (`/parsings/xml`, future) + generic schema validation (`/validations/*`, future); business schemas stay with the business service. |
| 2026-10-16 | §13 extended: reason_code → SOAP fault table (§13.2a); async outbound failure handling (§13.3a); retry policy clarified; payload size reality (§13.8, 40 MiB effective max); signer.verified_identity as authoritative sender (§13.9); claimed_signer_id regex relaxed (§13.10); self-loop replacement (§13.11) | sviljus review body items on #305 contract completeness |
| 2026-10-16 | §11.2 refined: dedicated `TURVIS_INTERNAL_TOKEN` (not shared `INTERNAL_SERVICE_TOKEN`); `mtls-or-token` expiry via `AUTH_MODE_TOKEN_EXPIRY`; alert metric `turvis_token_auth_after_mesh_total`; mTLS identity in SAN not DN | sviljus review body: avoid Turvis sign/decrypt oracle; prevent "temporary" overlap becoming permanent; SPIFFE-standard identity in SAN |
| 2026-10-16 | Rename leftovers: `sec_*` metric names → `turvis_*` (§10.3); audit-workspace `A9-xml-validator/` path refs scrubbed from DSL files and SPEC cross-references | sviljus review body: rename leftover cleanup |
| 2026-10-19 | §4.1/§4.2 `signature_digests` / `signatures` items now carry `reference_uri` + `transforms` (`subtree_xpath` optional, absent for `cid:` references); §4.1 verify `ebms_routing` is taken from the exact `<eb:Messaging>` covered by the verified signature (otherwise `SIG_POLICY_FAIL`); §4.4 decrypt `ebms_routing` is diagnostic only and §2.2 flow no longer requests it | #305 acceptance criteria (verified routing, NRR reference reconstruction), review of Turvis integration issues |
| 2026-10-20 | §13.0 step 2: security-decision discrepancies must be zero unexplained; the `< 0.01%` allowance applies only to non-security differences | #304 / #308 position; shadow gate must not hide a wrong accept/reject |
| 2026-10-20 | §7.2–7.4 rewritten: trust view generated from `registry/` (allowlist, no `apiKey`, pinning/chain per party, generation visibility, deny-wins status table) replacing the unapproved `parties.yml`; §13.0 ownership split (#305/#306/#308/#309/#312/#313), shadow invariants and start-up guard; §13.3 retry policy reconciled with §5; §13.6 deletions moved to #312; §9.1a attachment byte contract; `Klite` references removed | #304 merge conditions; **proposed, pending explicit agreement** |
| 2026-10-20 | §11.2 environment guard: `AUTH_MODE=shared-token` refused in prod/staging; §7.2 and §13.0 marked agreed | turnerrainer approval of the six decisions on PR #311 |
| 2026-10-20 | §4.0 mock caveat replaced: the mock (#306) validates and projects `return_fields`, rejects unknown values with a real `UNKNOWN_RETURN_FIELD`, extracts `ebms_routing` and the sender from the envelope, checks `key_handle`, and reports `<ds:Reference>` elements as written; §13.0 drift gate names the script and the unit test; DSL moved to `DSL/Ruuter-turvis-mock/` | #306 implementation (mock behaviour only, no change to the real contract) |
| 2026-10-22 | §4.2 "Signature placement": Turvis inserts exactly one `<ds:Signature>` as the last child of the existing `wsse:Security` header and changes no other byte of the envelope | #305c-1 review: edelivery refuses a signed envelope that is not the unsigned one plus that insertion; the position was an implementation assumption until now |
| 2026-10-22 | §4.1 `signature_digests` items gain the required `reference_xml_b64` (the received `ds:Reference` verbatim, self-contained); NRR receipts repeat it byte for byte instead of rebuilding a Reference from `reference_uri`/`digest_b64`/`transforms` (closes the missing `InclusiveNamespaces PrefixList` and digest-method gap of #305 AC 5). §13.7 text adjusted; §4.2 "Input parsing": sign refuses input that is not namespace-well-formed | edelivery cannot rebuild a Reference exactly from URIs; a peer comparing transform parameters would refuse the receipt |
