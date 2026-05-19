# Architecture: Security and Compliance

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Theme-wide architectural rules. Every sub-area below — and every Acceptance Criterion (AC) it carries — must derive from or at minimum **not conflict with** the rules stated here. AC live in the corresponding sub-area files under [`docs/cfr/security-and-compliance/`](../../cfr/security-and-compliance/); this document describes the *contract those AC implement*.

**System-wide reference:** [eFTI Gate Reference Architecture](../eFTI-Gate-Reference-Architecture.md). This document narrows the system-wide rules to the Security and Compliance surface.

**Sub-architectures in this theme** (each is the architectural surface for the AC tracked in the linked epic):

- [Security](security.md) — AC: [`docs/cfr/security-and-compliance/security.md`](../../cfr/security-and-compliance/security.md)
- [Audit and GDPR Compliance](audit_and_gdpr.md) — AC: [`docs/cfr/security-and-compliance/audit_and_gdpr.md`](../../cfr/security-and-compliance/audit_and_gdpr.md)

---

## Overarching rules

These are the cross-cutting invariants every sub-area in this theme derives from. AC bullets in the CFR files specialise them to specific endpoints, error codes, or DB state.

### 1.1 TLS 1.2+ end-to-end; no plaintext on the wire

All gate-facing traffic (Authority API, Admin API, Platform API, AS4 access point, gate-to-gate fast protocol, CronManager) is TLS 1.2 or higher. There is no plaintext fallback, no internal-network exception, no `--insecure` flag. Inter-pod traffic in a Kubernetes deployment is TLS too — the gate doesn't assume that "inside the cluster" is safe.

### 1.2 Personal data minimisation by design

The gate stores identifiers, denormalised search columns (`vehicle_plate`, `vehicle_country`, etc.), and routing metadata. It does **not** store CMDS dataset content. Personal data inside the dataset (driver name, consignee details, etc.) lives at the platform; the gate's persistent record carries only identifiers and the minimum search-axis fields. The platform is the GDPR data controller for dataset content; the gate is a processor for the search-index slice and the controller for its own operational tables (`users`, `audit_log`, `sessions`).

### 1.3 Audit log is append-only and write-only on the runtime path

`audit_log` is written by the gate runtime and read **only** by the audit/admin surface. The runtime `app` role has `SELECT, INSERT` only — no UPDATE/DELETE. Audit log entries are GDPR Art. 30 "record of processing" inputs: every authorisation denial, every dataset-retrieval request, every admin mutation is captured with caller identity, target resource, source IP, timestamp, and outcome. Retention satisfies EU Reg 2024/1942 audit windows; archival is owned by Theme 5.

### 1.4 Right to erasure handled at platform, not at gate

GDPR Art. 17 erasure requests are honoured by the **platform** (the dataset owner). The gate's record of the platform's dataset (identifier + search columns) follows the platform's lifecycle — when the platform deletes a dataset, the gate's `consignments` row gets a new `status='deleted'` row (append-only; the prior rows remain in the audit-anchored table until CronManager archives them). The gate does not delete `audit_log` entries on erasure requests — audit retention is a separate legal basis (Reg 2024/1942 audit trail).

### 1.5 Threat-model boundary: gate trusts proxies for TLS termination

The gate accepts mTLS terminated at a trusted reverse proxy (Envoy, Nginx, etc.) that forwards `X-Client-Cert-Subject` and `X-Client-Cert-Serial`. The proxy is part of the trust boundary; the gate does not validate these headers cryptographically. Operators are responsible for ensuring the proxy is not bypassable from outside the network perimeter. STRIDE per-surface threat model documentation is a deployment-time deliverable (see `docs/cfr/security-and-compliance/security.md` AC).

### 1.6 No secrets in container image; no secrets in logs

TLS certs, mTLS keys, TARA `client_secret`, break-glass JWT signing key, `ARCHIVE_OPS_TOKEN`, DB passwords — all loaded from a runtime secret store at startup. Container images are public-by-default (BUSL 1.1 source-available; see [`LICENSE`](../../../LICENSE)). Log statements never include credential material — log redaction filters strip `Authorization`, `X-API-Key`, `X-Client-Cert-Subject`, JWT bodies, and HTTP Basic credentials before ECS encoding.
