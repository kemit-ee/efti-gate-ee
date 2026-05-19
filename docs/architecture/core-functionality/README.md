# Architecture: Core Functionality

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Theme-wide architectural rules. Every sub-area below — and every Acceptance Criterion (AC) it carries — must derive from or at minimum **not conflict with** the rules stated here. AC live in the corresponding sub-area files under [`docs/cfr/core-functionality/`](../../cfr/core-functionality/); this document describes the *contract those AC implement*.

**System-wide reference:** [eFTI Gate Reference Architecture](../eFTI-Gate-Reference-Architecture.md). This document narrows the system-wide rules to the Core Functionality surface.

**Sub-architectures in this theme** (each is the architectural surface for the AC tracked in the linked epic):

- [Identifier Management (Platform API)](identifier_management.md) — AC: [`docs/cfr/core-functionality/identifier_management.md`](../../cfr/core-functionality/identifier_management.md)
- [Identifier Search (Authority API)](identifier_search.md) — AC: [`docs/cfr/core-functionality/identifier_search.md`](../../cfr/core-functionality/identifier_search.md)
- [Dataset Retrieval and Follow-up](dataset_retrieval_and_follow_up.md) — AC: [`docs/cfr/core-functionality/dataset_retrieval_and_follow_up.md`](../../cfr/core-functionality/dataset_retrieval_and_follow_up.md)
- [Identifier Search and Dataset Retrieval Flows](search_and_retrieval_flows.md) — AC: [`docs/cfr/core-functionality/search_and_retrieval_flows.md`](../../cfr/core-functionality/search_and_retrieval_flows.md)

---

## Overarching rules

These are the cross-cutting invariants every sub-area in this theme derives from. AC bullets in the CFR files specialise these rules to verifiable conditions on specific endpoints, error codes, or DB state.

### 1.1 The gate is a content-agnostic identifier registry — never a dataset store

The gate stores **identifiers**, **denormalised search columns**, and **routing metadata**. It never stores full CMDS dataset content. The platform owns dataset content for its full retention window; the gate only knows the identifier (`dataset_id`), the platform's binding (resolved from mTLS cert), and the small set of search fields denormalised onto `consignments` for the authority hot path. Any change that tempts the gate to store, parse, or transform dataset payloads must be rejected at the AC stage — dataset retrieval is a *routing* operation.

### 1.2 Append-only everywhere; latest-row reads

Every operational table (`consignments`, `identifiers`, `dataset_requests`, `follow_up_messages`, `request_id_cache`) is INSERT-only. State transitions (status flips, identifier expiry, request resolution) are new rows sharing the same logical id. Reads use the `DISTINCT ON (logical_id) … ORDER BY logical_id, created_at DESC` pattern — single-table, no `JOIN` on the hot path. This satisfies EU Reg 2024/1942 audit-trail requirements (every state was retained) without any UPDATE/DELETE machinery in the runtime role.

### 1.3 UIL is the addressing primitive

The Unique Identifier Locator (UIL) — a URL-shaped triple `(platformId, datasetId, gateId)` — is the only globally meaningful reference. The gate parses UILs, validates the `gateId` against `gates`, validates `platformId` against `platforms`, and forwards by URL. No alternative addressing scheme (numeric ID, opaque token) is exposed on the wire.

### 1.4 Broadcast only on zero local results

Authority identifier search first checks local registry. Only if **zero local rows match** does the gate broadcast to peer ONLINE gates over eDelivery AS4. The broadcast is best-effort with a configurable timeout; partial responses are returned. Broadcast is never the default behaviour even when remote evidence might exist — local-first protects authority latency and avoids load-amplification on every search.

### 1.5 Subset filtering is owned by the platform, not the gate

When an authority requests a dataset by UIL, the gate forwards the request (with the authority's `subsets` claim from the resolved `users` row) to the platform. The platform applies the subset filter and returns the filtered dataset. The gate never sees the unfiltered content; it only ferries the filtered response back to the requesting authority.

### 1.6 Request idempotency via `X-Request-ID`

All write-side Platform-API endpoints accept an `X-Request-ID` header and dedupe via the `request_id_cache` table (TTL 10 min). A repeated `X-Request-ID` within the TTL returns the cached response; past the TTL it is accepted as a new request. This protects against retried writes from flaky platform connectivity.
