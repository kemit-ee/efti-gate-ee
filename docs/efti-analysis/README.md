# Detailanalüüs (hange 303988) — säilinud osad

Eesti elektroonilise kaubaveoteabe (eFTI) infosüsteemi detailanalüüsi materjali allesjäänud osad. Algne hankedokument oli oluliselt mahukam; kõik osad, mis on uues v2 spetsifikatsioonis ([`../specs/`](../specs/)) asendatud, on uuest gate-i hoidlast eemaldatud — siia jäi ainult sisu, mida v2 spetsifikatsioon ei kata.

> **NB: v2.0 spetsifikatsioon (autoriteetne) asub [`../specs/`](../specs/) kataloogis.**

## Säilinud sisu

| Kaust | Sisu | Miks alles |
|---|---|---|
| [`1-analysis/`](1-analysis/) | `analysis_doc_eng.md` (97 KB), `analysis_doc_eng.docx`, `analysis_doc_est.docx` | Üldine projekti analüüs (eesmärk, missioon, ulatus, EU regulatsioonid). Annab uuele lugejale konteksti, mida v2 epicud ei sisalda. |
| [`3-model/`](3-model/) | `README.md`, `ACCESS.md`, `er-diagram.png` | ER-skeem on viidatud `../specs/db/README.md`-st; säilitatud sellega koos. |
| [`xsd/`](xsd/) | eFTI XML-skeemid (consignment-identifier, consignment-common, eDelivery, examples) | Kohustuslikud arendamiseks: epicud (3, 10, 25) viitavad neile. Säilitada täielikul kujul. |

## Eemaldatud osad

V2 spetsifikatsiooni poolt täielikult asendatud osad on uuest hoidlast eemaldatud:

- `2-openapi/` — vana v1 OpenAPI/Swagger määratlus → asendatud failiga [`../specs/openapi.yaml`](../specs/openapi.yaml).
- `4-rights-n-permissions/` → asendatud failiga [`../specs/permissions-matrix.md`](../specs/permissions-matrix.md).
- `5-errors-n-logging/` → asendatud failidega [`../specs/errors.json`](../specs/errors.json) ja [`../specs/logging-spec.md`](../specs/logging-spec.md).
- `6-transformations/` → asendatud failiga [`../specs/data-transformations.md`](../specs/data-transformations.md).
- `7-diagrams/` → asendatud kaustaga [`../specs/diagrams/`](../specs/diagrams/) (26 Mermaid diagrammi).
- `8-codereview/` — vana eFTI Gate PoC koodi-ülevaade (Klite/Kotlin/Svelte virnale); ei kohaldu uuele gate-i ehitusele.

Algne täielik detailanalüüs on säilitatud repos [kemit-ee/efti-gate-poc](https://github.com/kemit-ee/efti-gate-poc) `v0.2-askend-final` sildi all.
