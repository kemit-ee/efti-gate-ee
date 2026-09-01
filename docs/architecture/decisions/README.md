# Arhitektuuriotsused (ADR)

Siin kataloogis on kõik projekti arhitektuuriotsused, igaüks eraldi failis.
Otsused on loendurid ja ei kustutata — uus otsus ei asenda vana, vaid täiendab seda uue failiga.

| Nr | Pealkiri | Kuupäev | Otsustajad |
|---|---|---|---|
| [ADR-001](001-docker-compose-naming.md) | Docker Compose konteinerite nimetamine | 2026-08-12 | Sten Viljus |
| [ADR-002](002-status-over-isactive.md) | `is_active` asendamine `status` väljaga (gates, platforms) | 2026-08-14 | Sten Viljus, Anton Keks |
| [ADR-003](003-remove-supports-subsetting.md) | `supports_subsetting` eemaldamine platformi registrist | 2026-08-17 | Sten Viljus, Anton Keks |
| [ADR-004](004-platform-api-key.md) | Platvormide autentimine API võtmega (`X-Api-Key`) | 2026-08-25 | Rainer Türner, Sten Viljus, Anton Keks |
| ADR-005 | `m2m` Ruuteri eraldamine — *number on broneeritud harus `refactor/split-m2m-ruuter`, `dev`-is faili veel ei ole* | — | Sten Viljus |
| [ADR-006](006-xroad-identity-and-subsets.md) | X-Roadi identiteedimudel ja alamhulkade õigused | 2026-09-01 | täpsustamata |
