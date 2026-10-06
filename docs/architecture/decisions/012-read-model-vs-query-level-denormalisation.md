# ADR-012: Kuumade lugemiste denormaliseerimine — päringutaseme lahendus vs eraldi read-model tabelid

**Staatus: OTSUSTAMISEL** (arutelu: Sten Viljus, Rainer Türner). See on mustand, mitte vastu võetud otsus.

Seotud issue'd: kemit-ee/efti-gate-ee#246 (A6), #247–#251 (DENORM-1…5; varasemad #180–#185 on suletud ja asendatud). Seotud ADR: ADR-009.

## Probleem

Kliendi nõue on denormaliseeritud tabelid kuumadele lugemistele. Issue #246 jagab selle kaheks kihiks:

1. append-only allikatabelid monotoonsete revisjonidega — **olemas**;
2. denormaliseeritud tabelid, mida kuumad lugemised kasutavad, et revisjoniajalugu ei skannitaks — **puudub**.

Issue ootab, et staleness-taluvad lugemised (border-check, ajalugu, gate'i register, kokkuvõtted, admin-nimekirjad) käiksid ühe PK-otsinguga derivatiivtabelist, turvakriitilised lugemised
(`check_authority_subsets`, `check_user_auth`, `get_platform_by_api_key`, `insert_audit_log`, verify-after-write) aga jääksid allikalogi vastu.

Issue kommentaar ütleb, et vajadus on "lahendatud päringute tasemel". Selle ADR-iga tuleb otsustada, kas see on nõude täitmine või ei.

## Praegune seis (koodis)

- `consignments` kannab otsingukolonne otse (ei ole cross-table JOIN-e); see on kihi 2 osaline täitmine.
- ADR-009: `get_consignments.sql` filtreerib enne ja kontrollib "viimane rida" korreleeritud `NOT EXISTS`-iga; 1M real 23 s → mõõdetud kandidaat C6 (vt `docs/performance/askend_perf_verification/`).
- `check_transport_means_registered.sql` (border-check, >10 000/h, sub-sekund): indeksiga kandidaadid + `LATERAL` viimase rea otsing + `EXISTS … LIMIT 1`, ilma kogu tabeli sordita.
- `get_consignments_by_transport_means.sql`: sorditud indeksivaste + anti-join kuni 50 reani.
- `idx_consignments_created_latest (created_at DESC, revision DESC)` lubab laiad otsingud peatada LIMIT-il.
- Eraldi read-model tabeleid, refresh-töid ega projektsioone ei ole. `app` rollil on ainult `SELECT, INSERT`.

## Vaadeldud variandid

### A. Ainult päringutaseme optimeerimine (status quo + mõõtmine)

Allikatabel jääb ainsaks tõeks; kuumad päringud kasutavad filter-first + anti-join/LATERAL mustreid ja sobivaid indekseid.

- Pluss: ei ole teist tõe koopiat, ei ole refresh-viivet, ei ole 2× kirjutuskoormust, ei ole bloat-i ega cron-kattuvuse riski; ei vaja `app` rolli õiguste laiendamist.
- Miinus: hind sõltub ikka ajaloo suurusest (anti-join / LATERAL käib iga kandidaadi kohta); ei vasta kliendi nõude sõnastusele ("mandatory denormalised tables"); komposiitlugemised (gate + authority + user) jäävad mitme Resql-ringiga.

### B. Täielik read-model kõigile safe-list lugemistele (DENORM-1…5)

Iga safe-list kategooria saab derivatiivtabeli (üks rida võtme kohta), mida täidab CronManager kaudu Ruuter + ReSql (sama muster nagu arhiveerimine, ei mingeid DB-funktsioone).

- Pluss: täidab nõude sõna-sõnalt; PK-otsing on sõltumatu ajaloo suurusest; kuum rida püsib vahemälus.
- Miinus: refresh-viivitus (peab olema lühem kui rangeim test-fest tolerants); 2× kirjutuskoormus; kuumade võtmete dead-tuple bloat; cron vs burst-kirjutuste kattuvus; indeks vs tegelik päringumuster võib mööda minna; `UPDATE` on keelatud, seega read-model ei saa olla upsert-tabel; see peab olema ise append-only (vt otsus 3).

### C. Hübriid (soovitus)

Päringutaseme lahendus jääb DENORM-1 jaoks (border-check), kui mõõtmine tõestab sihtpiiri täitmist. Read-model tabelid tehakse ainult sinna, kus päringutasemest ei piisa: DENORM-2 (ajalugu), DENORM-4 (kokkuvõtted), DENORM-5 (admin-nimekirjad). DENORM-3 (gate'i register) otsustatakse eraldi, sest edelivery laeb party registry niikuinii 30 min tagant ja ADR-011 võib registri allkirjastatud konfiguratsiooniks muuta.

## Otsus (ettepanek: variant C)

1. **Mõõtmine enne ehitust.** DENORM-1 praegune lahendus mõõdetakse `python3 tests/sql/regression.py --performance` abil vähemalt 1M sünteetilisel real (hoiame sama metoodika, mis ADR-009). Sihtpiir: border-check p95 < 1 s samaaegsel kirjutuskoormusel. Kui täidetud, DENORM-1 suletakse päringutaseme lahendusena.
2. **Read-model on derivatiivne.** Allikatabel jääb kanooniliseks; read-model tabeli saab täielikult taastada allikast. Ühtegi turvakriitilist lugemist (loetelu issue's) read-model'ile ei suunata.
3. **Kirjutusmudel: read-model on ise append-only, `UPDATE` ei kasutata.**
   - **Inkrementaalne rida kirjutusteel** (DENORM-2, ajalugu): `insert_consignment` lisab samas tehingus read-model rea. Ei ole cron'i ega staleness'it; ajalugu on loomult append-only.
   - **Generatsiooni-snapshot** (DENORM-4 kokkuvõtted, DENORM-5 nimekirjad): CronManager → Ruuter → ReSql lisab terve uue snapshoti `generation = N` (INSERT-only). Eraldi väike INSERT-only osuti-tabel ütleb kehtiva generatsiooni (`ORDER BY generation DESC LIMIT 1`); osuti lisatakse alles pärast täielikku snapshoti kirjutust, nii et lugeja ei näe poolikut. Lugemine on `WHERE generation = :g AND key = :k` ühe PK-otsinguga, sõltumatu ajaloo pikkusest. Vanad generatsioonid kustutab CronManager (Quartz) Ruuteri + ReSql kaudu, nagu arhiveerimisel (`purge-archive` muster); kustutatakse ainult generatsioonid, mis on kehtivast vanemad.
   - **Hukka mõistetud:** eraldi roll `UPDATE` õigusega ja `MATERIALIZED VIEW ... REFRESH` (vajab omanikuõigust ja DB-objekti haldust, mis on vastuolus "ei mingeid DB-funktsioone" reegliga).
4. **Refresh-cadence** iga tabeli kohta fikseeritakse DSL/dokumentatsioonis ja peab olema lühem kui rangeim tarbija tolerants.
5. **Nõude sulgemine.** Kliendiga kinnitatakse kirjalikult, et variant C (mõõdetud päringutase DENORM-1 jaoks + tabelid ülejäänule) täidab nõude. Ilma selle kinnituseta jääb #246 lahtiseks.

## Tagajärjed

- #247 suletakse mõõtmistulemustega (kui sihtpiir täidetud); #248, #250, #251 jäävad ehitatavaks; #249 ootab ADR-011 otsust.
- Uued tabelid lisatakse Liquibase migratsiooni ja `DSL/Liquibase/init.sql`-i (hoitakse sünkroonis) ning kaetakse `tests/sql/regression.py` kontrollidega.
- `AGENTS.md` "Database rules" täiendatakse, kui variant C kinnitatakse (read-model tabelid on samuti INSERT-only; generatsioonide puhastus käib arhiveerimise mehhanismiga).

## Mõõtmistulemus (otsuse 1 täitmine)

`python3 tests/sql/regression.py --performance --rows 1000000` (sünteetiline, üks versioon dataset'i kohta, soe vahemälu; baseline = `origin/dev`):

| Päring | Täitmisaeg |
|---|---|
| `check_transport_means_registered` (border-check) | 0,38–0,45 ms |
| `get_consignments_by_transport_means` | 0,13–0,18 ms |
| `refresh_consignment_counts` (täis-snapshot) | ~1,7 s |
| `get_consignment_counts` (read-model lugemine) | 0,2 ms |

Border-check on sihtpiirist (p95 < 1 s) mitu suurusjärku allpool, seega DENORM-1 jääb päringutasemele. Piirang: mõõtmine ei kata samaaegset kirjutuskoormust ega pikki versiooniajalugusid.

## DENORM-5 rakendus

Admin registrinimekirjad (gates, platforms, authorities) loevad generatsiooni-snapshotist. Viivituse (nähtavuse lag pärast kirjutust) kõrvaldab refresh, mida iga admin-kirjutus-route kutsub kohe pärast kirjutust (best effort); cron on varuvariant. Tagajärg: iga kirjutus lisab uue generatsiooni (3 tabelit × registri suurus), mille cron purge'ib. Get-by-id, marsruutimine, autentimine ja õigused loevad endiselt allikatabelitest.

## Lahtised küsimused

- Kas versioonitud INSERT-only read-model (ilma generatsioonideta) annab piisavalt kasu võrreldes `LATERAL`-iga? Eeldatavasti mitte.
- Mis on kliendi aktsepteerimiskriteerium: tabelite olemasolu või mõõdetud jõudlus?
- Millised on ametlikud test-fest tolerantsid iga safe-list lugemise jaoks?
- Kas `check_authority_subsets` loend issue's vastab koodile (SQL-faili sellise nimega ei ole; subset-kontroll käib `get_authority_by_*` + guard'i kaudu)?
- Issue viitab kohalikule `acceptance-testing/1/A6-read-model-cqrs/` kaustale, mis ei ole selles repos.
