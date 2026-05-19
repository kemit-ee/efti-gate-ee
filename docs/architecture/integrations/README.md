# Architecture: Integrations

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Theme-wide architectural rules. Every sub-area below — and every Acceptance Criterion (AC) it carries — must derive from or at minimum **not conflict with** the rules stated here. AC live in the corresponding sub-area files under [`docs/cfr/integrations/`](../../cfr/integrations/); this document describes the *contract those AC implement*.

**System-wide reference:** [eFTI Gate Reference Architecture](../eFTI-Gate-Reference-Architecture.md). This document narrows the system-wide rules to the Integrations surface.

**Sub-architectures in this theme** (each is the architectural surface for the AC tracked in the linked epic):

- [eDelivery AS4 Integration](edelivery_as4.md) — AC: [`docs/cfr/integrations/edelivery_as4.md`](../../cfr/integrations/edelivery_as4.md)
- [X-Road Integration (EE extension)](x_road_integration.md) — AC: [`docs/cfr/integrations/x_road_integration.md`](../../cfr/integrations/x_road_integration.md)
- [eDelivery AS4 Message Flow](as4_message_flow.md) — AC: [`docs/cfr/integrations/as4_message_flow.md`](../../cfr/integrations/as4_message_flow.md)

---

## Overarching rules

These are the cross-cutting invariants every sub-area in this theme derives from. AC bullets in the CFR files specialise them to specific endpoints, error codes, or DB state.

### 1.1 Cross-border = eDelivery AS4; national = X-Road

The gate has exactly two integration buses. **Cross-border (peer eFTI gates):** eDelivery AS4 (SOAP 1.2 over HTTPS, WS-Security 1.1 with XML Signature SHA-256 and XML Encryption AES-128-GCM, EU-Trust-Service mTLS at the AS4 access point, 4-corner topology). **National (Estonian authority systems):** X-Road (RIA-operated). There is no third integration bus; new partners are onboarded by adding an entry in the relevant registry, never by adding a parallel transport.

### 1.2 Wire-strict envelope handling

Every AS4 envelope is XSD-validated on outbound *and* inbound. Signature verification is mandatory; envelopes with invalid or missing signatures are rejected before any payload is parsed. Encryption (XML-Enc, AES-128-GCM / RSA-OAEP) is mandatory at the conformance-profile floor; envelopes received unencrypted are rejected. The gate is content-agnostic for the payload inside the envelope (see [Core Functionality §1.1](../core-functionality/README.md)) but wire-strict for the envelope itself.

### 1.3 Asynchronous response routing via `LISTEN`/`NOTIFY`

Cross-gate requests are inherently async (the peer gate's response may take seconds to minutes). The originating gate stores an entry in `async_responses` keyed by `requestId`, and any gate node receiving the peer's reply writes the response row and emits a Postgres `NOTIFY`. The node that holds the in-flight HTTP handler `LISTEN`s on that channel and dispatches the response back to the caller. This keeps the gate horizontally scalable: no session affinity, no shared in-memory queue.

### 1.4 OCSP / CRL revocation checks fail closed

For mTLS at the AS4 access point, OCSP/CRL revocation checks on peer certificates **fail closed** — a check that cannot be performed (responder down, network error) is treated as revocation. The conservative default protects against accidentally trusting a revoked-but-unverifiable peer cert.

### 1.5 Gate-issued AS4 fast protocol is mTLS-only — no fallback

`POST /services/fast` (the gate-to-gate fast protocol bypassing Domibus) requires mTLS. There is no `X-API-Key` fallback. If a caller presents `X-API-Key`, it is never honoured. This eliminates an entire class of credential-leak risks at the cost of accepting that fast-protocol setup requires mTLS infrastructure.

### 1.6 X-Road = ID-token transport for Authority access only

X-Road is the transport for **Authority access from RIA-registered national systems** — it is not a substitute for AS4 (which is the cross-border bus). The X-Road message carries a TARA-issued ID token; the gate validates it identically to a direct TARA login (see [Identity & Access §1.1](../identity-and-access/README.md)). The X-Road integration is an Estonian national extension on top of the standard eFTI gate; other Member States' gates need not implement it.
