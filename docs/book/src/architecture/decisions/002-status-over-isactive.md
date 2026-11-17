# ADR-002: Replacing `is_active` with a `status` field (gates, platforms)

**Decision (Sten Viljus, Anton Keks, 14.08.2026):**

## Decision

`is_active` (BOOLEAN) is removed from the `gates` and `platforms` tables.
The canonical state field is `status` (`gate_status` ENUM).

## ENUM values

| Value | Meaning |
|---|---|
| `ONLINE` | Active and reachable (last ping succeeded) |
| `OFFLINE` | Unreachable (ping failed or never pinged) |
| `DISABLED` | Administratively switched off; visible in lists, but does not take part in queries |
| `DELETED` | Soft delete by the operator; the row remains in the database for auditing |

## Behaviour rules

- **Soft delete** = a new row with `status = 'DELETED'` (per the append-only logic)
- **List filter** = `WHERE status != 'DELETED'` (visible: ONLINE, OFFLINE, DISABLED)
- **Single-record query** (`GET /gates?gateId=...`) also returns a DELETED record (visible for audit)
- the `authorities` and `users` tables keep `is_active` for now (a separate decision is needed)

## Rationale

`is_active` and `status` were two overlapping state fields on the same record, which created ambiguity:
- Which one is authoritative?
- Is `is_active=FALSE, status=ONLINE` allowed?
- What does `is_active=TRUE, status=DISABLED` mean?

An ENUM is canonical, self-documenting and extensible without a schema change.

## Applied changeset

`DSL/Liquibase/changelogs/002-status-refactor.sql`
