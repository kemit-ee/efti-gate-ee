# Decision maker

> **Goal:** you can say what the gate does and does not hold, how it can be run, what you remain
> responsible for, and what must be true before it carries real data.

No commands on this page. Technical readers can follow the links.

## What the gate does

The gate is routing infrastructure under Regulation (EU) 2020/1056. It connects three kinds of
partner and answers one national question: *which consignments exist for this vehicle, and may this
authority see them?*

- **Freight platforms** register consignment identifiers.
- **Competent authorities** search those identifiers and ask for datasets, nationally over X-Road.
- **Other member states' gates** exchange the same requests over eDelivery AS4 when the data is
  abroad.

[Find your path](./index.md) has the four-corner picture. The full behaviour is in the
[use cases](../use-cases/index.md).

## What it holds, and what it does not

| The gate holds | The gate does not hold |
|---|---|
| Identifier records a platform uploaded (the identifier XML and searchable columns such as transport identifiers, mode, country) | The consignment **dataset** (FTI010). It is fetched from the platform on demand and passed through, filtered to the requesting authority's subsets |
| Users who may administer it, an audit log, message and follow-up logs | Platform credentials in a database. They are in the registry files (see below) |
| Short-lived search state for cross-gate searches (purged after minutes) | Passwords of administrators. Login is TARA |

Consequences you can use in a data-protection assessment:

- A breach of the gate database exposes identifiers and metadata, not the freight datasets.
- Every table is append-only; history is retained and can be archived. See
  [Append-only archival](../architecture/infrastructure/append_only_archival.md).
- Personal codes in the audit log are stored hashed. See
  [Audit and GDPR](../architecture/security-and-compliance/audit_and_gdpr.md).

## Deployment models

The software is the same in both models. What differs is who operates it.

| | Run it yourself (on-premise or your own cloud) | Hosted by a provider |
|---|---|---|
| Who runs the database, certificates, upgrades | You | The provider |
| Who edits the registry (platforms, authorities, peer gates) | You | The provider, on your request, unless the contract says otherwise |
| Data residency | Your choice | The provider's region |
| Your integration effort | Install, secrets, monitoring, plus the integrations below | The integrations below only |
| Time to first connection | Longer: you build the operating capability first | Shorter |

> This book describes the models in general. It does not offer, or commit anyone to, operating a
> gate as a service.

In both models **someone** must do the work in [Deployer](./deployer.md) and
[Operator](./operator.md). A hosted model moves those pages to the provider; it does not remove
them.

What the gate needs, whoever runs it, is on [Deployer](./deployer.md): a PostgreSQL 18 instance
for live data and a second for archive, an identity provider (TARA through TIM), an AS4 key pair, an
X-Road Security Server for authorities, and a scheduler (CronManager) for clean-up jobs.

## What you still own

- **Your platforms and authorities.** The gate does not certify platforms. A platform or authority
  exists because an entry in the registry says so; the registry is reviewed in git
  ([Change the registry](./registry-changes.md)).
- **Certificates.** Your AS4 certificate is exchanged with every peer gate and rotated together with
  them ([Peer gate](./peer-gate-operator.md)).
- **Who may administer the gate.** There are no roles: every active user has full access to the
  admin API. This is a limit of the current design ([Permissions matrix](../data/permissions.md)); role separation is a separate piece of work.
- **Production cryptography.** See the next section.

## Production readiness

Read these before relying on this build for real data.

> **Cryptography.** The AS4 signing, signature verification and payload encryption inside the
> repository's own `edelivery` service (`TURVIS_INTEGRATION_MODE=legacy`, the default) are intended
> for evaluation and testing. The project's position is that production uses the separate **Turvis**
> component, which is opt-in (`TURVIS_INTEGRATION_MODE=turvis`) and not yet the default. The stated
> condition for switching it on is real-service interoperability and an enforced network perimeter.
> See the [Perimeter trust model](../architecture/security-and-compliance/perimeter-trust-model.md) and
> [Turvis](../api/internal/turvis.md).

Other points that a decision should weigh:

- **Registry carries live secrets.** Platform `apiKey`s are plaintext in `registry/platforms/*.yml`
  (ADR-011 §5). In production they come from a Secret, never from an image. See
  [ADR-011](../architecture/decisions/011-registries-as-signed-config.md).
- **Release signing of the registry is not implemented** (ADR-011 §2 and §8 are requirements, not
  features).
- **Known defects** are listed, not hidden: [Known issues](../reference/known-issues.md).
- **Dev fixtures must not ship.** The default Liquibase context seeds development users, and the
  repository's `registry/` holds dev entries including a public key. The deployer page lists the
  switches.

## Where to read more

- Design and decisions: [Technical architecture](../architecture/index.md), in particular
  [Security](../architecture/security-and-compliance/security.md) and the
  [decision records](../architecture/decisions/index.md).
- Who may do what: [Permissions matrix](../data/permissions.md).
- Next, for the people who will do the work: [Deployer](./deployer.md).
