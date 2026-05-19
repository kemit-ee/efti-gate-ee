# EPIC 10 — eDelivery AS4 Integration

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: Integrations](README.md). Architecture: [integrations/README.md](../../architecture/integrations/README.md) (theme-wide rules) + [integrations/edelivery_as4.md](../../architecture/integrations/edelivery_as4.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** eFTI Gate
**I WANT** to communicate with other EU gates via the eDelivery AS4 protocol
**SO THAT** cross-border eFTI data exchange uses the standard EU infrastructure.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **API operations** | `POST /services/msh` (AS4 inbound) |
| | `POST /services/fast` (mTLS fast protocol) |
| | Full request / response / error shapes: [`openapi.yaml`](../../specs/openapi.yaml) |
| **Schema** | `async_responses` (peer-gate async responses; routed back to the owning node via `LISTEN/NOTIFY`) |
| | `request_id_cache` (outbound `requestId` dedup) |
| | Full schema: [`db/schema.sql`](../../specs/db/schema.sql) |
| **XML schemas** | [`gate.xsd`](../../efti-analysis/xsd/edelivery/gate.xsd) (eDelivery message schema) |
| **Wire transformations** | JSON ↔ AS4 envelope, WS-Security sign + encrypt, SOAP fault → RFC 7807: [`data-transformations.md`](../../specs/data-transformations.md) |
| **Protocol pinning** | EU eDelivery AS4 1.15 conformance profile; XML Signature SHA-256; XML Encryption AES-128-GCM; `eb:Action` literals: `identifierQuery`, `identifierResponse`, `uilQuery`, `uilResponse`, `postFollowUpRequest`, `followUpResponse` — [`non-functional.md`](../../specs/non-functional.md) §3, §4 |
| **Error codes** | `GATEWAY_UNAVAILABLE` |
| | `GATE_TIMEOUT` |
| | `EDELIVERY_ERROR` |
| | Full catalog: [`errors.json`](../../specs/errors.json) |
| **Architecture** | [RA §4 Protocol Architecture](../../architecture/eFTI-Gate-Reference-Architecture.md#4-protocol-architecture-generic-envelope--variable-payload) |
| | [RA §5.1 Identifier Query](../../architecture/eFTI-Gate-Reference-Architecture.md#51-identifier-query-cross-border-search) |
| **Diagrams** | [`seq-14-gate-to-gate-search.mmd`](../../specs/diagrams/seq-14-gate-to-gate-search.mmd) |
| | [`seq-16-mtls-fast-protocol.mmd`](../../specs/diagrams/seq-16-mtls-fast-protocol.mmd) |
| | [`arch-02-gate-network.mmd`](../../specs/diagrams/arch-02-gate-network.mmd) |
| **Architecture** | [../../architecture/integrations/README.md](../../architecture/integrations/README.md) (theme rules) + [../../architecture/integrations/edelivery_as4.md](../../architecture/integrations/edelivery_as4.md) (sub-architecture) |

## Acceptance Criteria

### Inbound AS4 messages

**Business rules:**
- [ ] `POST /services/msh` accepts SOAP/AS4 messages; the gate decrypts and parses per the AS4 1.15 conformance profile.
- [ ] Inbound `eb:Action` family handled by the gate: `identifierQuery`, `uilQuery`, `postFollowUpRequest`, `saveIdentifiersRequest` (each maps to its corresponding handler in the local-gate flow).
- [ ] Outbound responses for the above: `identifierResponse`, `uilResponse`, `followUpResponse`, etc.

**Denial scenarios:**
- [ ] Unknown `eb:Action` → AS4 fault returned to the sender; logged WARN. Never silently ignored.
- [ ] Unknown `CompressionType` → AS4 fault returned; never silently decompressed.
- [ ] Invalid AS4 signature → rejected; logged WARN with the sender Party ID.
- [ ] SOAP-parse failure → AS4 fault with error code and description.

### Outbound AS4 + Fast-protocol

**Business rules:**
- [ ] The outbound gate-to-gate client emits one structured log entry per call carrying: target gate id, chosen protocol (`fast` / `eDelivery`), URL, duration ms, HTTP / SOAP status, error.
- [ ] eDelivery client also logs: destination Party ID, `requestId`, duration ms, response status.
- [ ] Fast protocol: `POST {gate.eDeliveryUrl}` with **mTLS** (no `X-API-Key`; see Epic 2).
- [ ] eDelivery: SOAP message encrypted + signed via WS-Security **before** sending.
- [ ] An outbound failure (timeout / 5xx / SOAP fault) is logged at ERROR with full context; the originating caller receives a `502`-class response.

### Protocol envelope and request generation

**Business rules:**
- [ ] Every outbound request envelope (`identifierQuery`, `uilQuery`, etc.) **must validate against the eDelivery XSD before sending**. An XSD-invalid envelope is a `500` (logged ERROR) — never forwarded.
- [ ] Every outbound carries a `requestId` (UUID v4) for the audit trail.
- [ ] The gate is **content-agnostic** for dataset payloads — the dataset body is wrapped in the eDelivery envelope and forwarded unchanged.
- [ ] The same envelope-build path operates across all transport modes without mode-specific logic.

### Asynchronous response handling

**Business rules:**
- [ ] Async responses (`uilResponse`, `identifierResponse`) are persisted to `async_responses` and dispatched back to the originating in-flight request via PostgreSQL `LISTEN/NOTIFY` — no session affinity required.
- [ ] The dispatch handler runs on every gate node; each node consumes only responses matching the `requestId` of its in-flight callers.
- [ ] An async response that arrives **after** its originating SSE stream has closed is discarded and logged at DEBUG.

## Implementation contract

- [ ] The AS4 access point **must** be protocol-compatible with the EU eDelivery AS4 1.15 conformance profile. Operator's choice between the embedded AS4 implementation (Askend baseline) and [Domibus](https://ec.europa.eu/digital-building-blocks/sites/display/DIGITAL/Domibus). No bespoke / non-conformant AS4 stack.
- [ ] WS-Security signing key is loaded from a runtime secret (K8s Secret / vault) — never baked into the container image.

<!-- issue-body:end -->
