# THEME 3 — Registry Management


**Objective:** Give administrators full control over the foundational data that drives gate operations — the EU gate list, registered platforms, competent authorities, and stored consignments — all manageable via the Admin API without direct database access.

**Business value:** Registries are the foundation of gate operation. Incorrect or missing registry data causes search failures, incorrect broadcasts, or unauthorised access. Data changes must synchronise in real time to all running nodes.

**Theme done when:**
- [ ] EPIC 6 (Gates): gate CRUD + ping + LISTEN/NOTIFY sync done
- [ ] EPIC 7 (Platforms): platform CRUD + connectivity ping + subsetting flag done
- [ ] EPIC 8 (Authorities): authority CRUD + subset assignment done
- [ ] EPIC 9 (Consignments): identifier expiry + CMDS lifecycle done


## Epics

- [EPIC 6 — Gate Registry Management (Admin API)](epic_6_en.md)
- [EPIC 7 — Platform Registry Management (Admin API)](epic_7_en.md)
- [EPIC 8 — Authority Registry Management (Admin API)](epic_8_en.md)
- [EPIC 9 — Consignment Management (Admin API)](epic_9_en.md)
