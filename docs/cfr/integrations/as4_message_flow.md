# EPIC 25 — eDelivery AS4 Message Flow

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: Integrations](README.md). Architecture: [integrations/README.md](../../architecture/integrations/README.md) (theme-wide rules) + [integrations/as4_message_flow.md](../../architecture/integrations/as4_message_flow.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** technical architect
**I WANT** documented eDelivery AS4 message flows with sequence diagrams
**SO THAT** developers understand exactly how inter-gate messages travel through the AS4 protocol.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **Underlying epic** | Epic 10 (eDelivery AS4 Integration) — the AC source of truth. This epic provides the **visual** companion. |
| **API operations shown** | `POST /services/msh` (AS4 inbound), `POST /services/backend` (AS4 outbound), `GET /datasets/{datasetId}` (platform-side). Full shapes: [`openapi.yaml`](../../specs/openapi.yaml) |
| **XML schemas** | [`gate.xsd`](../../efti-analysis/xsd/edelivery/gate.xsd) |
| **Wire transformations** | XML → AS4 envelope, WS-Security sign + encrypt, SOAP fault → RFC 7807: [`data-transformations.md`](../../specs/data-transformations.md) |
| **Protocol pinning** | EU eDelivery AS4 1.15 conformance profile: [`non-functional.md`](../../specs/non-functional.md) §3, §4 |
| **Companion mermaid files** | [`seq-14-gate-to-gate-search.mmd`](../../specs/diagrams/seq-14-gate-to-gate-search.mmd) |
| | [`seq-16-mtls-fast-protocol.mmd`](../../specs/diagrams/seq-16-mtls-fast-protocol.mmd) |
| **Architecture** | [../../architecture/integrations/README.md](../../architecture/integrations/README.md) (theme rules) + [../../architecture/integrations/as4_message_flow.md](../../architecture/integrations/as4_message_flow.md) (sub-architecture) |
| | [RA §4 Protocol Architecture](../../architecture/eFTI-Gate-Reference-Architecture.md#4-protocol-architecture-generic-envelope--variable-payload) |
| | [RA §5.1 Identifier Query](../../architecture/eFTI-Gate-Reference-Architecture.md#51-identifier-query-cross-border-search) |

> **Implementation choice (not mandated by EU regs).** The diagrams show "eDelivery AS4 AP" generically. Operators may use the gate's embedded AS4 implementation (Askend baseline) or [Domibus](https://ec.europa.eu/digital-building-blocks/sites/display/DIGITAL/Domibus) — both are protocol-compatible per Reg 2024/1942 Art 11.

<!-- issue-body:end -->
