# EPIC 11 — X-Road Integration (EE extension)

## Changes

- **v1.3** — `POST /xroad/v1/transport-means` implemented: a registration number in, the identifier-level
  data the gate holds out, EU02-gated, local registry only, no dataset content. This substantially
  covers the **ANTS existence-check** AC below, except that it returns the known data rather than a
  bare `{"registered": …}`. Two AC corrections that must be made **in the GitHub issue**, not here:
  the read path is `consignments.main_transport_id` (the column `vehicle_plate` has never existed in
  the schema), and the p95 < 1 s SLO is unverified — there is no performance harness in the repo.
- **v1.2** — The X-Road surface moved into the main gate Ruuter as the `xroad/` project
  (`DSL/Ruuter/xroad/`, served under `/xroad/` on port 8086); the separate `ruuter-xroad` container
  and port 8087 are gone. Network isolation is now an ingress constraint (`/xroad/**` not publicly
  routable). See [ADR-006](../../architecture/decisions/006-xroad-identity-and-subsets.md).
- **v1.1** — Spec anchors corrected: the surface is **REST, not SOAP** (no WSDL, no
  `protocolVersion`), the adapter is a Ruuter project rather than a Java
  `ee-adapter` module, and subset permissions come from `authorities.subsets`, not `users.subsets`.
  See [ADR-006](../../architecture/decisions/006-xroad-identity-and-subsets.md).
  **The Acceptance Criteria below still carry the SOAP-fault and `users.subsets` wording** — that
  section is owned by GitHub issue #51 once created and is preserved across syncs by
  `scripts/sync-epic-to-issue.py`, so it must be corrected in the GitHub UI, not here.
- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: Integrations](README.md). Architecture: [integrations/README.md](../../architecture/integrations/README.md) (theme-wide rules) + [integrations/x_road_integration.md](../../architecture/integrations/x_road_integration.md) (sub-architecture).

<!-- issue-body:begin -->



## Spec anchors

| Contract surface | Reference |
|---|---|
| **X-Road service** | `EE/GOV/70003158/efti-gate/{operation}/v1` (one service per gate REST operation exposed via X-Road) |
| **Protocol** | **X-Road v7 REST message protocol — not SOAP.** No WSDL. No `protocolVersion` header (the version is the consumer-side `/r1/` prefix, never forwarded to the provider). |
| **Module boundary** | `DSL/Ruuter/xroad/` (Ruuter project `xroad`, served under `/xroad/` on port 8086) → `core` (the `efti` project on the same Ruuter) via published REST API only. `core` carries no X-Road references, and `template:` does not cross project boundaries. There is no `ee-adapter` Gradle module. |
| **Adapter surface** | One project-level guard `xroad/.guard.yml` (+ `xroad/GET/health/.guard.yml` `override_ancestors` for the public probe). Routes: `POST /xroad/v1/transport-means` (local vehicle lookup, EU02-gated, no dataset content), `POST /xroad/v1/{dataset,search,follow-up}` (forward to core over its published REST API), `POST /xroad/v1/echo` (diagnostic, outside the versioned contract), `GET /xroad/v1/subsets`, `GET /xroad/health/ready`. **`/xroad/**` must not be exposed on the public ingress.** |
| **Identity** | `X-Road-Client` (`instance/memberClass/memberCode[/subsystemCode]`) → `authorities.registry_code`. Organisation-level; `X-Road-UserId` is audit-only and never grants access. |
| **Subset permissions** | `authorities.subsets TEXT[]` — **not** `users.subsets`, which does not exist |
| **Underlying REST contract** | [`openapi.yaml`](../../specs/openapi.yaml) — the same Admin / Authority / Platform routes the adapter forwards to |
| **Error codes emitted today** | `UNAUTHORIZED` (401, missing/malformed `X-Road-Client`), `MISSING_REQUIRED_HEADER` (400, missing `X-Road-Id`), `INVALID_REQUEST_ID` (400, `X-Road-Id` not UUID-shaped — the gate maps it to a typed-UUID internal request id), `FORBIDDEN` (403, `memberCode` resolves to no `ACTIVE` authority **or** to more than one — a registry misconfiguration), `MISSING_SUBSET` (400, empty/absent subset list), `FORBIDDEN_SUBSET` (403, requested subset outside `authorities.subsets`), `GATEWAY_UNAVAILABLE` (502, `core` answered ≥ 400; its status and body carried in `coreStatus` / `coreResponse`) |
| | All gate-side errors surface as **RFC 7807-shaped JSON**, not SOAP faults (Ruuter serves them as `application/json`, matching every other guard in the gate) |
| | Full catalog: [`errors.json`](../../specs/errors.json) |
| **ADR** | [ADR-006 — X-Roadi identiteedimudel ja alamhulkade õigused](../../architecture/decisions/006-xroad-identity-and-subsets.md) |
| **Architecture** | [../../architecture/integrations/README.md](../../architecture/integrations/README.md) (theme rules) + [../../architecture/integrations/x_road_integration.md](../../architecture/integrations/x_road_integration.md) (sub-architecture) |
| | [RA §9.1 Platform API](../../architecture/eFTI-Gate-Reference-Architecture.md#91-platform-api) |
| | [RA §1 System Actors](../../architecture/eFTI-Gate-Reference-Architecture.md#1-system-actors--components) (X-Road, ANTS, NES) |
| **Diagrams** | [`seq-10-platform-registration.mmd`](../../specs/diagrams/seq-10-platform-registration.mmd) (X-Road variant of the platform-registration flow) |

## Acceptance Criteria

### X-Road adapter

**Business rules:**
- [ ] The X-Road service surface lives **entirely in the `ee-adapter` module**; the `core` module carries **zero** X-Road references.
- [ ] The adapter validates the X-Road headers (`client`, `service`, `id`, `protocolVersion`) before forwarding.
- [ ] Client identity is established by the X-Road Security Server (mTLS); the gate trusts the adapter-forwarded identity.
- [ ] The adapter calls `core` only via the public REST API. Modifying `core` to add X-Road awareness is **not permitted**.

**Denial scenarios:**
- [ ] Unknown `protocolVersion` → SOAP fault `faultCode="Client.unknownVersion"`.
- [ ] Client identity not authorised → SOAP fault carrying a 403-class error.
- [ ] Core REST returns 4xx/5xx → wrapped as X-Road SOAP fault; the underlying RFC 7807 payload is carried in the fault detail.

### Estonian competent authorities

**Business rules:**
- [ ] Each authority picks its preferred channel — eDelivery AS4 **or** X-Road. Both are supported.

**Denial scenarios:**

### ANTS integration (high-throughput existence check)

**Business rules:**
- [ ] The gate exposes a dedicated **read-only** existence-check endpoint for ANTS — returns `{"registered": true|false}`, no dataset content.
- [ ] Channel: X-Road, via the NES intermediary (MTA internal system).
- [ ] SLO: p95 < 1 s (ANTS may send > 10 000 queries/hour during border operations).
- [ ] **No broadcast.** A miss is `{"registered": false}` — the existence check does **not** trigger the cross-gate broadcast that the regular Authority `/v1/identifiers/{identifier}` route would.
- [ ] Read path: index-only scan on `consignments.vehicle_plate` (no extra column reads).

### ADR 1000-point rule (subset `EU05`)

**Business rules:**
- [ ] The gate calculates the ADR 1.1.3.6 dangerous-goods point total per vehicle (UN number × hazard class × net mass) and appends it to the `EU05` subset response.
- [ ] Calculation result: `≥ 1000` → full ADR; `< 1000` → partial (1.1.3.6 exemptions apply); `= 0` → full exemption.
- [ ] If the platform advertises `supportsAdrCalculation=true`, the gate **skips its own calculation** and passes the platform's value through unchanged.
- [ ] If `supportsAdrCalculation=false`, the gate calculates and appends.

<!-- issue-body:end -->
