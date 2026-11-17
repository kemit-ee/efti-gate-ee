# ADR-003: Removing the `supports_subsetting` field from the platform registry

**Decision (Sten Viljus, Anton Keks, 17.08.2026):**

## Decision

`supports_subsetting` (BOOLEAN) is removed from the `platforms` table and from all API layers.

## Rationale

Under the regulation, every platform must perform subsetting on its own side — it is an obligation, not an option. Therefore holding a `supports_subsetting` flag in the registry is no longer meaningful: the value is always assumed to be `TRUE` and no exceptions are allowed.

Keeping the flag caused:
- Ambiguity: does `FALSE` mean non-compliance or simply not-registered?
- False flexibility: the API accepted `supports_subsetting: false`, which implies that a platform *may* not perform subsetting
- Extra complexity in branching the subsetting logic

## Consequences

- the `platforms` table no longer contains a `supports_subsetting` column
- neither `POST /api/v1/platforms` nor `PUT /api/v1/platforms` accepts or returns this field
- existing platforms that had `supports_subsetting = FALSE` need to be brought into compliance with the regulation
- the subsetting logic always assumes the platform itself performs subsetting

## Applied changeset

`DSL/Liquibase/changelogs/003-remove-supports-subsetting.sql`

## Addendum 2026-10-26 — who enforces subsetting (#326)

- **The platform MUST** subset the response and **the gate does not check the response content**: it forwards the platform's response verbatim. Reason: a REST platform's response is a bare `SpecifiedSupplyChainConsignment` without a subset marker, and the gate does not know the authoritative element → subset mapping (the repo XSD marks only `EU01`, `EU02`, `EU03` and `EU06`). Technical content checking is a separate project that would be triggered only by a regulator requirement and an authoritative definition.
- The gate checks the **request**: the subsets requested by an X-Road authority must belong to its registry record's `subsets` (`FORBIDDEN_SUBSET`); the internal authority routes require at least one subset and only the codes `EU01..EU07`.
- **Only** the canonical `EU01..EU07` are allowed (the registry converter rejects anything else already at build time). Country-code subsets (`EE01` and the like) are not subsets at the gate; they are the platforms' business and stay inside the consignment XML.
- Traceability (an audit row for each data return) is currently missing; #360 tracks it.
