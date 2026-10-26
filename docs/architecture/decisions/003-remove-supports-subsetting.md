# ADR-003: `supports_subsetting` välja eemaldamine platformi registrist

**Otsus (Sten Viljus, Anton Keks, 17.08.2026):**

## Otsus

`supports_subsetting` (BOOLEAN) eemaldatakse `platforms` tabelist ning kõigist API kihtidest.

## Põhjus

Regulatsiooni kohaselt peab iga platvorm teostama subsettimist enda poolel — see on kohustus, mitte valikvõimalus. Seetõttu ei ole `supports_subsetting` lipu hoidmine registris enam tähenduslik: väärtus on alati eelduslikult `TRUE` ja erandeid ei lubata.

Lipu säilitamine tekitas:
- Ebamäärasust: kas `FALSE` tähendab mittejärgivust või lihtsalt registreerimata?
- Vale paindlikkust: API aktsepteeris `supports_subsetting: false`, mis vihjab, et platvorm *võib* subsettimist mitte teha
- Lisakompleksust subsettimise loogika hargnemiseks

## Tagajärjed

- `platforms` tabel ei sisalda enam `supports_subsetting` veergu
- `POST /api/v1/platforms` ega `PUT /api/v1/platforms` ei aktsepteeri ega tagasta seda välja
- Olemasolevad platvormid, millel oli `supports_subsetting = FALSE`, vajavad vastavusse viimist regulatsiooniga
- Subsettimise loogika eeldab alati, et platvorm ise teeb subsettimist

## Rakendatav changeset

`DSL/Liquibase/changelogs/003-remove-supports-subsetting.sql`

## Täiendus 2026-10-26 — kes subsettimist jõustab (#326)

- **Platvorm PEAB** vastust subsettima ja **värav ei kontrolli vastuse sisu**: ta edastab platvormi vastuse sõna-sõnalt. Põhjus: REST-platvormi vastus on paljas `SpecifiedSupplyChainConsignment` ilma alamhulga märgendita ning väravale ei ole teada autoriteetne element → alamhulk vastendus (repo XSD märgib ainult `EU01`, `EU02`, `EU03` ja `EU06`). Tehniline sisukontroll on eraldi projekt, mille käivitaks alles regulaatori nõue ja autoriteetne määratlus.
- Värav kontrollib **päringut**: X-tee asutuse taotletud alamhulgad peavad kuuluma tema registrikirje `subsets`-i (`FORBIDDEN_SUBSET`), sisemised autoriteedi marsruudid nõuavad vähemalt ühe alamhulga ja ainult koode `EU01..EU07`.
- Lubatud on **ainult** kanoonilised `EU01..EU07` (registri konverter keelab muu juba ehitamisel). Riigikoodiga alamhulgad (`EE01` jms) ei ole väravas alamhulgad; need on platvormide asi ja jäävad konsignatsiooni XML-i sisse.
- Jälgitavus (iga andmetagastuse auditirida) puudub praegu; seda jälgib #360.
