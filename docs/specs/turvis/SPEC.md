# Turvis — Integration Contract

> **Turvis** is a Bürostack component focused on security-sensitive operations: hardened payload parsing, schema validation (XSD, JSON Schema, …), and cryptography (XMLDSig verify/sign, XML Encryption decrypt/encrypt). It is designed as a shared Bürostack component — any fleet service that needs these operations can call it over the same HTTP contract.
>
> **Scope of THIS document.** The Phase 1 slice of the Turvis contract that the eFTI gate's `edelivery` service consumes: the four cryptography endpoints (verify, sign, encrypt, decrypt). Payload-validation and hardened-parse endpoints are planned follow-ups under the same `/turvis/v1/<operation>/<mechanism>` URL shape (§3.1); they are **not** part of what the kemit-ee dev team integrates in #305–#308.
>
> **Audience**: the kemit-ee dev team implementing #305–#308 (edelivery integration + Phase-1 mock + perimeter trust + cutover).
>
> **Not in this document.** How Turvis implements the crypto, stores keys, rotates them, deploys, runs tests, or chooses a stack — all **Turvis's responsibility**, tracked separately when the turvis repo is provisioned (external tracker, TBD, e.g. `turnerrainer/turvis`).
>
> **Contract source of truth**: the Ruuter DSL files at `dsl-contracts/turvis/POST/v1/*/*.yml` are the AUTHORITATIVE REST contract. This document's §4 JSON Schemas are companion documentation for details Ruuter DSL `allowlist:` can't express (nested arrays, `format: base64`, oneOf accept/reject, enum constraints). See `dsl-contracts/README.md` for the contract-change workflow.
>
> **Last updated**: 2026-10-16.

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
- Trust store + private-key access (how it's loaded, rotated, stored — all **Turvis's responsibility**, see §7)
- Audit log of every security decision
- `/metrics` endpoint
- Health probes

**Future scope (same component, later phases — not in this contract)**:
- Payload-validation endpoints (XSD, JSON Schema, …)
- Hardened-parse endpoints (XML, JSON, …) — eventually replaces the in-process `XmlParser` in `edelivery` and in any other Bürostack service that currently rolls its own parse
- Content-type sniffing / payload classification

**Permanently out of scope (never in Turvis)**:
- Business logic (eFTI FTI004/FTI007 semantic processing stays in edelivery / xml-mapper)
- SOAP envelope assembly (stays in edelivery — framing, not security)
- Peer registry CRUD (stays in edelivery; Turvis reads a trust store, not a registry)
- MIME multipart assembly on outbound AS4 (stays in edelivery — framing)
- X-Road integration
- UI
- WebSockets / SSE / streaming

---

## 2. System boundary + data flow

### 2.1 Services Turvis talks to

```
                ┌───────────────────────────────┐
                │ edelivery                     │  ──┐
                │ (eFTI gate, Klite-based)      │    │ HTTP calls over mTLS mesh
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
peer AS4 → Ruuter envelope checks (A8)
       → edelivery /services/msh
       → edelivery parses multipart (standard framework, non-crypto)
       → edelivery POSTs /turvis/v1/decryptions/xmlenc    ← Turvis
       → edelivery POSTs /turvis/v1/verifications/xmldsig ← Turvis
       → edelivery parses MessageHeader with hardened XmlParser
       → edelivery dispatches to FTIxxxHandler
       → handler returns response data to edelivery
```

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
| POST | `/turvis/v1/validations/xmlschema` | Validate XML against a registered XSD. |
| POST | `/turvis/v1/validations/jsonschema` | Validate JSON against a registered JSON Schema. |
| POST | `/turvis/v1/parsings/xml` | Hardened XML parse; returns a structured representation or a reject verdict. |

These will be specified in a follow-up revision of this document when the sec-service team is ready to deliver them; for now they are listed only to anchor the extensibility story in §3.1.

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

A new mechanism is a drop-in sibling file in `dsl-contracts/` (e.g. `verifications/jws.yml`) with its own `allowlist:`, response schema, and reason-code set added to §5 as a dedicated subsection. A new operation family is a new top-level directory. No change to existing leaves; no `/v2/` bump. edelivery picks a leaf per-route (XMLDSig for AS4; JWS for a hypothetical JSON peer), not via content negotiation on a single endpoint.

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

### 4.1 `POST /turvis/v1/verifications/xmldsig`

**Purpose**: verify the inbound XMLDSig (W3C XML Signature) on an AS4 SOAP envelope + enforce signed-subtree policy.

#### Request body schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["soap_xml_b64", "attachments", "claimed_signer_id", "required_signed_xpaths", "required_signed_cid_refs"],
  "properties": {
    "soap_xml_b64": {
      "type": "string",
      "format": "base64",
      "description": "Base64 of the raw SOAP envelope as received from the peer. Exact bytes — must not be re-serialised by caller."
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
            "description": "Base64 of the attachment bytes AFTER decryption (edelivery decrypts first via /turvis/v1/decryptions/xmlenc)."
          }
        }
      },
      "maxItems": 10
    },
    "claimed_signer_id": {
      "type": "string",
      "pattern": "^[A-Z]{2}-[A-Z0-9]{2,8}$",
      "description": "The sender party ID as claimed in <eb:From><eb:PartyId>. Format: 'EU-XX' or 'EU-XXX-NN' etc."
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
    "trust_store_version": {
      "type": "string",
      "description": "Optional. If caller wants verification against a specific trust-store generation (for shadow-mode comparison). Omit for default."
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

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["verdict", "signer", "matched_signed_xpaths", "matched_signed_cid_refs", "audit_ref"],
  "properties": {
    "verdict": {"const": "accept"},
    "signer": {
      "type": "object",
      "required": ["cert_subject_dn", "cert_ski", "cert_serial", "verified_identity"],
      "properties": {
        "cert_subject_dn": {"type": "string"},
        "cert_ski": {"type": "string", "description": "Hex-encoded Subject Key Identifier."},
        "cert_serial": {"type": "string", "description": "Decimal serial number."},
        "verified_identity": {
          "type": "string",
          "description": "The party ID this signature was verified as. Must match claimed_signer_id or Turvis returns reject with SIGNER_IDENTITY_MISMATCH."
        }
      }
    },
    "matched_signed_xpaths": {
      "type": "array",
      "items": {"type": "string"},
      "description": "Subset of required_signed_xpaths that were covered by SignedInfo."
    },
    "matched_signed_cid_refs": {
      "type": "array",
      "items": {"type": "string"}
    },
    "audit_ref": {
      "type": "string",
      "format": "uuid",
      "description": "Opaque UUID for this verification decision. Used for log cross-reference."
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

#### Request body schema

```json
{
  "type": "object",
  "required": ["soap_xml_b64", "sign_xpaths", "sign_cid_refs", "key_handle"],
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
    "key_handle": {
      "type": "string",
      "description": "Fixed per gate = OWN_GATE_ID. See §7."
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
    "signatures": {
      "type": "array",
      "description": "Metadata about what was signed. Non-authoritative; the actual signature is embedded in the signed_xml_b64 bytes.",
      "items": {
        "type": "object",
        "properties": {
          "subtree_xpath": {"type": "string"},
          "digest_b64": {"type": "string"},
          "algorithm": {"type": "string"}
        }
      }
    },
    "audit_ref": {"type": "string", "format": "uuid"}
  }
}
```

#### Response — reject (422 problem+json)

Same shape as verify reject, with `type: "https://efti.example/problem/turvis-sign-xmldsig-reject"`.

#### Idempotency

**NOT byte-equal under retry.** XMLDSig signatures include deterministic-but-input-dependent fields (digest values); same input → same output bytes, **assuming** no randomness in signing path (RSA-PKCS1-v1_5 is deterministic; RSA-PSS is not). This spec requires RSA-SHA256 which is PKCS1-v1_5, so **signatures ARE byte-identical** under retry.

### 4.3 `POST /turvis/v1/encryptions/xmlenc`

**Purpose**: WS-Encryption of outbound payload for a specific receiver.

#### Request body schema

```json
{
  "type": "object",
  "required": ["plaintext_bytes_b64", "receiver_party_id"],
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
    "trace_id": {"type": "string", "pattern": "^[0-9a-f]{32}$"}
  }
}
```

#### Response — accept (200 OK)

```json
{
  "type": "object",
  "required": ["wrapped_key_b64", "encrypted_payload_b64", "wrapped_key_xml", "encrypted_data_xml", "audit_ref"],
  "properties": {
    "wrapped_key_b64": {
      "type": "string",
      "format": "base64",
      "description": "Base64 of RSA-OAEP(receiver_cert, ephemeral_aes_key)."
    },
    "encrypted_payload_b64": {
      "type": "string",
      "format": "base64",
      "description": "Base64 of AES-128-GCM(ephemeral_aes_key, iv || ciphertext || tag). edelivery MIME-wraps as attachment."
    },
    "wrapped_key_xml": {
      "type": "string",
      "description": "The <xenc:EncryptedKey> XML block, ready to splice into SOAP header. Includes KeyInfo + CipherValue."
    },
    "encrypted_data_xml": {
      "type": "string",
      "description": "The <xenc:EncryptedData> XML block, ready to splice into SOAP header. Includes CipherReference pointing at cid:message."
    },
    "audit_ref": {"type": "string", "format": "uuid"}
  }
}
```

#### Response — reject (422 problem+json)

Reasons: `RECEIVER_UNKNOWN`, `RECEIVER_CERT_EXPIRED`, `RECEIVER_CERT_UNTRUSTED`, `PAYLOAD_TOO_LARGE`, `ALGO_UNSUPPORTED`.

#### Idempotency

**NOT byte-equal under retry.** Each call generates a fresh ephemeral AES key + random IV. Each response is a valid WS-Encryption envelope for the same plaintext. Functionally equivalent, byte-different. edelivery must handle retries as "new call" (don't dedup).

### 4.4 `POST /turvis/v1/decryptions/xmlenc`

**Purpose**: WS-Encryption decrypt of inbound payload with gate's private key.

#### Request body schema

```json
{
  "type": "object",
  "required": ["wrapped_aes_key_b64", "encrypted_payload_b64", "key_handle"],
  "properties": {
    "wrapped_aes_key_b64": {
      "type": "string",
      "format": "base64",
      "description": "The <xenc:EncryptedKey><xenc:CipherValue> content from the SOAP envelope. Base64 of RSA-OAEP(our_cert, aes_key)."
    },
    "encrypted_payload_b64": {
      "type": "string",
      "format": "base64",
      "description": "The AES-GCM-encrypted attachment bytes (iv || ciphertext || tag)."
    },
    "key_handle": {
      "type": "string",
      "description": "Fixed per gate = OWN_GATE_ID."
    },
    "claimed_recipient_ski": {
      "type": "string",
      "description": "Hex SKI from the inbound <wsse:KeyIdentifier>. Turvis checks this matches the key behind key_handle. Rejects with SKI_MISMATCH if not."
    },
    "trace_id": {"type": "string", "pattern": "^[0-9a-f]{32}$"}
  }
}
```

#### Response — accept (200 OK)

```json
{
  "type": "object",
  "required": ["plaintext_bytes_b64", "audit_ref"],
  "properties": {
    "plaintext_bytes_b64": {
      "type": "string",
      "format": "base64",
      "description": "Base64 of the decrypted business payload (gzip-compressed; edelivery decompresses after)."
    },
    "audit_ref": {"type": "string", "format": "uuid"}
  }
}
```

#### Response — reject (422 problem+json)

Reasons: `WRAPPED_KEY_MALFORMED`, `KEY_HANDLE_UNKNOWN`, `SKI_MISMATCH`, `AES_UNWRAP_FAIL`, `PAYLOAD_DECRYPT_FAIL`, `ALGO_UNSUPPORTED`.

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
| `KEY_UNAVAILABLE` | 503 | signatures, decryptions | KMS temporarily unreachable; retry safe. |
| `WRAPPED_KEY_MALFORMED` | 422 | decryptions | RSA-OAEP ciphertext malformed. |
| `SKI_MISMATCH` | 422 | decryptions | `claimed_recipient_ski` does not match the SKI of the key behind `key_handle`. |
| `AES_UNWRAP_FAIL` | 422 | decryptions | RSA-OAEP decrypt returned error. |
| `PAYLOAD_DECRYPT_FAIL` | 422 | decryptions | AES-GCM decrypt returned error (likely integrity tag mismatch — payload tampered). |
| `RECEIVER_UNKNOWN` | 422 | encryptions | `receiver_party_id` not in trust store. |
| `RECEIVER_CERT_EXPIRED` | 422 | encryptions | Receiver cert past `notAfter`. |
| `RECEIVER_CERT_UNTRUSTED` | 422 | encryptions | Receiver cert does not chain to trust store. |
| `PAYLOAD_TOO_LARGE` | 413 | encryptions | Payload exceeds `MAX_PAYLOAD_BYTES` (default 50 MiB). |
| `ALGO_UNSUPPORTED` | 422 | all | Fallback for any algorithm choice not in the pinned allowlist. |
| `TRUST_STORE_UNAVAILABLE` | 503 | all | Trust store load failed; retry safe. |
| `TURVIS_INTERNAL` | 500 | all | Internal bug. Retry NOT safe without investigation. |

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

## 7. Key material + trust store — **Turvis's responsibility**

Private-key format/handover, trust-store format, rotation procedure, and KMS integration are all internal to Turvis. The dev team never touches them.

**What the dev team DOES need to know (edelivery-side):**

- `key_handle` field (passed on every `/signatures/xmldsig` + `/decryptions/xmlenc` call): **fixed per gate = `OWN_GATE_ID`** (e.g. `EU-EE`). Turvis rejects with `KEY_HANDLE_UNKNOWN` if the handle doesn't match its loaded key. No multi-key support in v1.
- `trust_store_version` field (optional on `/verifications/xmldsig`): opaque string, if present pins which trust-store version the verifier should use. Dev team can leave it unset to accept whichever version is loaded.
- edelivery's `partyRegistry` **is no longer the authority** on peer certs after A9. Keep it for admin UI display + routing (`party.eDeliveryUrl` for outbound HTTP); drop any code path that reads `party.cert` for signature verification or encryption-target lookup.

Everything else (p12 handover, rotation runbook, KMS choice, trust-store manifest signing) is handled by the turvis team.

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

### 9.2 Policy enforcement on inbound verify

Every verification call carries `required_signed_xpaths` + `required_signed_cid_refs`. Turvis rejects with `SIG_POLICY_FAIL` if any required subtree is not in `SignedInfo.references`.

Policy table edelivery passes per call:

| Route (edelivery context) | required_signed_xpaths | required_signed_cid_refs |
|---|---|---|
| AS4 inbound `/services/msh` | `["//eb:Messaging", "//env:Body"]` | `["cid:message"]` |
| AS4 outbound sign (same subtrees) | same | same |

The policy table lives in edelivery config, not turvis state. Turvis's log hygiene, mesh-layer rejection of non-authorised clients, and egress restrictions are **Turvis's responsibility** — not duplicated here.

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
| `sec_requests_total` | counter | `endpoint`, `verdict` | Request count. |
| `sec_request_duration_seconds` | histogram | `endpoint` | Latency. Buckets: 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5. |
| `sec_rejections_total` | counter | `endpoint`, `reason_code` | Reject count. |
| `sec_trust_store_entries` | gauge | — | Count of entries in trust store. |
| `sec_trust_store_last_reload_timestamp` | gauge | — | Unix timestamp of last successful reload. |
| `sec_build_info` | gauge | `version`, `git_sha` | Static. |

Cardinality: `reason_code` label is bounded (see §5 enum). `endpoint` label is bounded (4 values). Do NOT add peer-party-id as a metric label — unbounded.

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

A3 (strict mTLS mesh + NetworkPolicy) is a hard dependency on this build's final deployment, but ship-timeline-wise it may not be ready at first turvis deploy. During the interim window Turvis MUST support the fleet's existing shared-token pattern as a fallback authn mechanism:

- **Mechanism**: `X-Internal-Service-Token` request header. Shared-secret, compared constant-time via `MessageDigest.isEqual()` (JVM) or `subtle::ConstantTimeEq` (Rust).
- **Secret source**: environment variable `INTERNAL_SERVICE_TOKEN` (secret-ref). Same convention as the fleet's `code/core/src/.../InternalServiceTokenAuth.kt`.
- **Enforcement toggle**: environment variable `AUTH_MODE` with values:
  - `mtls` (default, production target) — requires strict mTLS; rejects any request without valid client cert from the configured ServiceAccount.
  - `shared-token` (interim) — accepts requests that present a valid `X-Internal-Service-Token` header.
  - `mtls-or-token` (migration overlap) — accepts either. Logs a warning when a request arrives via `shared-token` so operators can track migration progress.
- **Fail-closed**: on startup, if `AUTH_MODE=shared-token` or `mtls-or-token` and `INTERNAL_SERVICE_TOKEN` is unset or equals the known-weak default `dev-internal-service-token-change-me`, Turvis refuses to boot.
- **Migration guidance**: deploy initially with `AUTH_MODE=mtls-or-token`; after mesh enrollment is verified for edelivery, flip to `AUTH_MODE=mtls` and remove the token secret from the deployment.
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
- Metrics increment: `edelivery_sec_calls_total{endpoint=..., verdict="accept"}`
- Proceed with returned data

On 422 (reject):
- Parse problem+json body
- Log `audit_ref` cross-ref
- Metrics increment: `edelivery_sec_calls_total{endpoint=..., reason_code=...}`
- Map `reason_code` to a specific SOAP fault code (for AS4 inbound) OR specific internal exception (for admin paths)

On 500 / 503 / network error:
- Log + metric
- Treat as transient; return 503 Service Unavailable upstream

### 13.3 Timeout + retry

- Per-call HTTP timeout: 10 seconds (well above p99 budget of 150ms)
- Retries: NOT enabled on inbound verify / decrypt (idempotent but one-shot — peer retries on timeout)
- Retries: NOT enabled on outbound sign / encrypt (encrypt is not byte-idempotent; retrying would use a new AES key per call, confusing downstream)

### 13.4 Byte-fidelity preservation

See §9.1 — hard contract.

### 13.5 Policy declaration

For every verification call, edelivery declares `required_signed_xpaths` and `required_signed_cid_refs` per the policy table in §9.2. Policy table lives in edelivery config, not turvis state.

### 13.6 Code deletions on cutover

After A9-SUB-4 cutover:

**Private key + crypto code:**
- `code/edelivery/src/edelivery/KeyManager.ownPrivateKey` field: deleted
- Private-key secret volume mount: removed from edelivery container
- `code/edelivery/src/edelivery/SignatureValidator.kt`: deleted
- `code/edelivery/src/edelivery/Xml.kt::canonicalXml()`: deleted
- `code/edelivery/src/edelivery/EDeliveryMessageGenerator`: signing + encryption methods deleted
- `code/edelivery/src/edelivery/EDeliveryRoutes.decryptPayload`: body deleted (HTTP call to `/turvis/v1/decryptions/xmlenc` remains)

**Data-model cleanup (MessageHeader stays ebMS-only):**
- Any fields on `MessageHeader` referring to signature or encryption state — deleted. `MessageHeader` is the `<eb:Messaging>` element: business routing + correlation only (From, To, Service, Action, MessageId, RefToMessageId, ConversationId, timestamps). Signature + encryption live in `<wsse:Security>` as siblings and are handled by Turvis, not parsed into `MessageHeader`.
- `WssSecurityHeader` / `WssSignatureInfo` / `WssEncryptedKey` data classes (if present): deleted. edelivery no longer needs a model for the `<wsse:Security>` element — Turvis takes the raw SOAP bytes and returns verdict JSON.
- `CertificateChainValidator`, `TrustStoreLoader`, or similar cert-handling code: deleted. Signer identity comes to edelivery as `signer.cert_subject_dn` + `signer.cert_ski` fields in Turvis's verify response.

### 13.7 Record-level fields for audit / incident response

For audit, non-repudiation receipts, and incident lookup, edelivery stores a small set of per-message fields **alongside** the message record (new columns on the existing message/audit table, or a side table — edelivery's call). These are NOT inside `MessageHeader`; they are record metadata.

| Field | Source | Purpose |
|---|---|---|
| `turvis_audit_ref_verify` | Turvis verify response `audit_ref` | Lookup via `GET /turvis/v1/audit/{ref}` on incident |
| `turvis_audit_ref_decrypt` | Turvis decrypt response `audit_ref` | Same |
| `turvis_audit_ref_sign` | Turvis sign response `audit_ref` | Same (outbound) |
| `turvis_audit_ref_encrypt` | Turvis encrypt response `audit_ref` | Same (outbound) |
| `signer_identity` | Turvis verify response `signer.verified_identity` | Audit log + business authorisation |
| `signer_cert_ski` | Turvis verify response `signer.cert_ski` | Operator-facing provenance; survives cert rotation |

If/when AS4 non-repudiation receipts (NRR) are implemented, the signature digests needed for the receipt come from Turvis's verify response (optional `signature_digests` field, to be added to SPEC §4.1 at that time). Still not stored in `MessageHeader`.

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
  "claimed_signer_id": "EU-FR",
  "required_signed_xpaths": ["//eb:Messaging", "//env:Body"],
  "required_signed_cid_refs": ["cid:message"],
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

**Response**:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "verdict": "accept",
  "signer": {
    "cert_subject_dn": "CN=EU-FR-gate,O=DGITM",
    "cert_ski": "a1b2c3d4e5f6...",
    "cert_serial": "12345678901234567890",
    "verified_identity": "EU-FR"
  },
  "matched_signed_xpaths": ["//eb:Messaging", "//env:Body"],
  "matched_signed_cid_refs": ["cid:message"],
  "audit_ref": "9f3e2a9b-1234-5678-90ab-cdef12345678"
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
  "trace_id": "..."
}
```

**Response**:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "signed_xml_b64": "PGVudjpFbnZlbG9wZSB4bWxuczp...",
  "signatures": [
    {"subtree_xpath": "//eb:Messaging", "digest_b64": "...", "algorithm": "SHA-256"},
    {"subtree_xpath": "//env:Body", "digest_b64": "...", "algorithm": "SHA-256"}
  ],
  "audit_ref": "..."
}
```

### 14.4 Encrypt — accept

**Request**:

```http
POST /turvis/v1/encryptions/xmlenc HTTP/1.1
Content-Type: application/json

{
  "plaintext_bytes_b64": "H4sIAAAAAAAA...",
  "receiver_party_id": "EU-FR",
  "trace_id": "..."
}
```

**Response**:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "wrapped_key_b64": "aGVsbG8gd29ybGQ=",
  "encrypted_payload_b64": "AAAAAAAAAAAAAAAA...",
  "wrapped_key_xml": "<xenc:EncryptedKey xmlns:xenc=\"...\" Id=\"EK-ID\">...</xenc:EncryptedKey>",
  "encrypted_data_xml": "<xenc:EncryptedData xmlns:xenc=\"...\">...</xenc:EncryptedData>",
  "audit_ref": "..."
}
```

### 14.5 Decrypt — accept

**Request**:

```http
POST /turvis/v1/decryptions/xmlenc HTTP/1.1
Content-Type: application/json

{
  "wrapped_aes_key_b64": "aGVsbG8gd29ybGQ=",
  "encrypted_payload_b64": "AAAAAAAAAAAAAAAA...",
  "key_handle": "EU-EE",
  "claimed_recipient_ski": "a1b2c3d4e5...",
  "trace_id": "..."
}
```

**Response**:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "plaintext_bytes_b64": "H4sIAAAAAAAA...",
  "audit_ref": "..."
}
```

---

## 15. Glossary

- **AS4**: OASIS ebMS 3.0 Advanced AS4 messaging profile. Specifies SOAP-with-attachments transport for business messages between e-delivery gateways.
- **ebMS 3.0**: OASIS Electronic Business Messaging Services version 3.0. SOAP-based messaging specification.
- **edelivery**: the Kotlin/Klite-based service in the eFTI gate that handles AS4 inbound/outbound traffic.
- **exc-c14n**: W3C Exclusive XML Canonicalisation 1.0. Tree-based deterministic serialisation algorithm for XMLDSig.
- **FTI004 / FTI007 / etc.**: eFTI message schemas defining the business payload structure.
- **KMS**: Key Management Service. Cloud / HSM-backed private key holder (AWS KMS, GCP KMS, HashiCorp Vault, etc.).
- **Klite**: an in-house Kotlin HTTP framework by the fleet. The gate's `edelivery` + `xml-mapper` services are built on it.
- **mTLS**: mutual TLS. Client + server both present + verify certificates.
- **OWASP XXE Prevention**: canonical hardening guide for XML parsers against external entity attacks.
- **RFC 7807**: HTTP problem+json error response format.
- **SKI**: X.509 Subject Key Identifier. Hash of the public key, usable as a stable identity reference.
- **SwA**: SOAP with Attachments. OASIS profile for binding non-XML content to SOAP messages via MIME multipart.
- **Trust store**: collection of trusted peer certs used for signature verification + encryption-target lookup.
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
