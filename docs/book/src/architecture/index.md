# Technical architecture

How the gate is designed: its components, the flows between them, the trust model, and the decisions
that shaped it. This part is the home of the architecture documents (previously under
`docs/architecture/`).

> **Design documents, not the verified interface reference.** These pages describe the architecture
> and the reasoning behind it. Some are reconciled with the running code; some describe target state
> under the eFTI Regulation (which fully applies from 9 July 2027) and use illustrative endpoint
> shapes. Where a page and the code disagree, the **[API reference](../api/index.md)** wins — it is
> generated against, and its examples were run on, the local stack. Read this part for *why* and
> *how it fits together*; read the API reference for the *exact* current request and response.

## Start here

- [Reference architecture](./reference-architecture.md) — the technology-agnostic model: actors,
  the UIL, identifiers vs datasets, message flows, what the gate does and does not do.
- [Architecture baseline](./baseline.md) — the component plan (Ruuter, ReSql, TIM, the Kotlin
  services) and the routing patterns that implement it.

## Themes

- [Core functionality](./core-functionality/index.md) — identifier registration and search, dataset
  retrieval and follow-up, and the end-to-end data flows.
- [Identity and access](./identity-and-access/index.md) — authentication channels, the
  database-backed authorisation snapshot, and user management / RBAC.
- [Integrations](./integrations/index.md) — eDelivery AS4 (cross-border), X-Road (national), and the
  internal Kotlin services, plus [multi-node response correlation](./multi-node.md).
- [Registry management](./registry-management/index.md) — the gate, platform and authority
  registries, and consignment management.
- [Security and compliance](./security-and-compliance/index.md) — the perimeter trust model,
  security controls, and audit / GDPR.
- [Observability](./observability/index.md) — logging, monitoring and alerting.
- [Infrastructure](./infrastructure/index.md) — statelessness and scaling, health checks and
  graceful shutdown, append-only archival.
- [Software quality](./software-quality/index.md) — API standardisation, CI/CD and supply chain,
  test coverage.
- [User interfaces](./user-interfaces/index.md) — the admin UI and the authority UI.

## Decisions

- [Decision records (ADRs)](./decisions/index.md) — the numbered architecture decisions, with
  context, the decision, and its consequences.
