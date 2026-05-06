# eFTI Gate — Estonia (KeMIT)

Estonian implementation of the **eFTI Gate**: a national node in the EU electronic freight transport information network defined by [EU Regulation 2020/1056](https://eur-lex.europa.eu/eli/reg/2020/1056/oj). The Gate registers freight identifiers, mediates dataset retrieval between certified eFTI Platforms and competent authorities, and bridges to peer national gates over eDelivery AS4. Full regulatory effect: **9 July 2027**.

## Where to start

| You want to | Read |
|---|---|
| 1-page project map (themes / epics) | [`PROJECT-OVERVIEW.md`](PROJECT-OVERVIEW.md) |
| Authoritative artifact list + design rules | [`docs/planning/SPEC-INDEX.md`](docs/planning/SPEC-INDEX.md) |
| Acceptance criteria, per epic | [`docs/epics/`](docs/epics/) |
| OpenAPI 3.0 contract | [`docs/specs/openapi.yaml`](docs/specs/openapi.yaml) |
| PostgreSQL schema | [`docs/specs/db/schema.sql`](docs/specs/db/schema.sql) |
| Sequence / state / flow diagrams | [`docs/specs/diagrams/`](docs/specs/diagrams/) |
| Reference architecture | [`docs/architecture/eFTI-Gate-Reference-Architecture.md`](docs/architecture/eFTI-Gate-Reference-Architecture.md) |

## Repository layout

- **`docs/specs/`** — authoritative v2 specifications: OpenAPI, PostgreSQL schema, errors catalog, logging spec, permissions matrix, data transformations, 26 Mermaid diagrams.
- **`docs/epics/`** plus **`docs/efti_full_epics_{en,et}.md`** — 25 epics across 9 themes (English + Estonian canonical aggregate; English split per epic).
- **`docs/architecture/`** — target reference architecture per EU Reg 2020/1056, 2024/1942, 2024/2024, 2025/2243.
- **`docs/planning/`** — `SPEC-INDEX.md` (entry point for new readers and LLMs) plus the KeMIT prompts that drove the v2 specification generation.
- **`docs/efti-analysis/`** — Askend Estonia OÜ procurement-deliverable analysis (hange 303988); historical, superseded by `docs/specs/` for v2 work.

## Status

This repository contains the **specification corpus** for the v2 eFTI Gate. Implementation work follows once the spec is signed off.

The `feature/planning` branch carries the active consolidation work; `main` is the integration branch.

## Maintainer

KeMIT — Riigi Infosüsteemi Amet (Estonia)
Contact: `help@kemit.ee`

## Licence

This work is licensed under [Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)](https://creativecommons.org/licenses/by-nc/4.0/). Free to share and adapt for non-commercial use, with attribution to KeMIT, Estonia.

**Commercial use requires a separate licence.** Contact `help@kemit.ee` with the intended use case, the licensee entity, and the geographic scope. See [`LICENSE`](LICENSE) for the full terms.
