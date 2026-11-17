# Architecture Decision Records (ADR)

This directory contains all of the project's architecture decisions, each in its own file.
Decisions are counters and are never deleted — a new decision does not replace an old one, it
supplements it with a new file.

> **One exception (08.10.2026).** ADR-014, ADR-015 and ADR-016 were progressive refinements of a
> single decision (registries as configuration) and were **consolidated into [ADR-011](011-registries-as-signed-config.md)**,
> which now describes the actual state (the signature is still outstanding). The content of those
> three files is in ADR-011; the numbers 014–016 are free and **the next new decision continues from
> 017**. Comments in already-applied Liquibase migrations still refer to the old numbers — changing
> them would break the checksums.

| No. | Title | Date | Deciders |
|---|---|---|---|
| [ADR-001](001-docker-compose-naming.md) | Docker Compose container naming | 2026-08-12 | Sten Viljus |
| [ADR-002](002-status-over-isactive.md) | Replacing `is_active` with a `status` field (gates, platforms) | 2026-08-14 | Sten Viljus, Anton Keks |
| [ADR-003](003-remove-supports-subsetting.md) | Removing `supports_subsetting` from the platform registry | 2026-08-17 | Sten Viljus, Anton Keks |
| [ADR-004](004-platform-api-key.md) | Platform authentication with an API key (`X-Api-Key`) — **amended by [ADR-011](011-registries-as-signed-config.md) §5**: the key is plaintext in the registry, SHA-256 was dropped | 2026-08-25 | Rainer Türner, Sten Viljus, Anton Keks |
| ADR-005 | Separating the machine interface (`m2m`) — **reviewed (Sten Viljus, Anton Keks, 03.09.2026): instead of a separate Ruuter instance, a separate project in the main Ruuter; it can be moved to another Ruuter later if needed.** The file is still missing in `dev`. | 2026-08-31 / 2026-09-03 | Sten Viljus, Anton Keks |
| [ADR-006](006-xroad-identity-and-subsets.md) | X-Road identity model and subset permissions | 2026-09-01 | unspecified |
| [ADR-007](007-xroad-transport-means-response-shape.md) | `transport-means` `scope: allgates` response shape — **DECISION: option A** (xml-mapper normalisation, issue #125) | 2026-09-03 / 2026-09-11 | unspecified |
| [ADR-008](008-adopt-datamapper.md) | Adopting DataMapper for response shaping — **DRAFT** (related to ADR-007) | 2026-09-03 | unspecified |
| [ADR-009](009-consignments-latest-row-not-exists.md) | Selecting the latest `consignments` version with `NOT EXISTS`, not `DISTINCT ON` | 2026-09-09 | Sten Viljus |
| [ADR-010](010-authority-search-non-blocking.md) | Authority search does not block; dedup of overlapping guards; DSL tools from the runtime image | 2026-09-10 | Sten Viljus |
| [ADR-011](011-registries-as-signed-config.md) | Registries (gates, platforms, authorities) as signed configuration, taking effect with a release — **implemented**, except for the signature (see §2/§8) | 2026-10-01 | Sten Viljus, Rainer Türner |
| [ADR-012](012-read-model-vs-query-level-denormalisation.md) | Denormalising hot reads: a query-level solution vs read-model tables — **UNDER DECISION** | 2026-10-05 | Sten Viljus, Rainer Türner |
| [ADR-013](013-retire-multiplexer-ruuter-fanout.md) | Removing the klite multiplexer — fan-out and state in the Ruuter DSL (K4) | 2026-10-07 | Rasmus |
