# Theme: Registry Management

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Architecture: [`../../architecture/registry-management/README.md`](../../architecture/registry-management/README.md). The overarching rules are defined there; the AC below verify the gate honours those rules end-to-end.

<!-- issue-body:begin -->

**Objective:** Give administrators full control over the foundational data that drives gate operations — the EU gate list, registered platforms, competent authorities, and stored consignments — all manageable via the Admin API without direct database access.

## Business value



## Acceptance Criteria

**Theme done when:**
- [ ] EPIC 6 (Gates): gate CRUD + ping + LISTEN/NOTIFY sync done
- [ ] EPIC 7 (Platforms): platform CRUD + connectivity ping + subsetting flag done
- [ ] EPIC 8 (Authorities): authority CRUD + subset assignment done
- [ ] EPIC 9 (Consignments): identifier expiry + CMDS lifecycle done

<!-- issue-body:end -->

## Sub-areas

- [Gate Registry Management (Admin API)](gate_registry.md)
- [Platform Registry Management (Admin API)](platform_registry.md)
- [Authority Registry Management (Admin API)](authority_registry.md)
- [Consignment Management (Admin API)](consignment_management.md)
