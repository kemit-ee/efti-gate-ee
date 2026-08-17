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
