# ADR-011: Registrid (gate'id, platvormid, asutused) allkirjastatud konfiguratsioonina, release'iga jõustuvad

**Otsus (01.10.2026, täiendatud 08.10.2026 — Sten Viljus, Rainer Türner; rakendus Anton Keks):**
Gate'ide, platvormide ja volitatud asutuste register **ei ole** runtime'i REST-ressurss ega andmebaasi
tabel. See on repos olev konfiguratsioon (`registry/`), mida muudetakse PR-i kaudu, mis
konverteeritakse **build-ajal** JSON-iks ja mida serveeritakse staatiliselt. **Allkirjastamine
(§2, §8) on endiselt nõue, mida ei ole veel rakendatud** — see on selle ADR-i peamine lahtine punkt.

> **Staatus: rakendatud, välja arvatud allkiri.** Algne eelnõu (01.10.2026) kirjeldas
> laadurit + allkirjastatud manifesti + eraldi DB-rolli; rakendus jättis allkirja tegemata ja
> vahepealse andmebaasikihi üldse ära. Käesolev tekst kirjeldab **tegelikku seisu** ja hoiab
> allkirjanõude jõus.
>
> **Numeratsioon.** Selle otsuse järkjärgulised täpsustused olid vahepeal eraldi failidena
> (ADR-014, ADR-015, ADR-016) ja need on nüüd **konsolideeritud käesolevasse ADR-i**; nende failid
> on kustutatud ja järgmine uus otsus jätkab numbriga **017**. Erand: **juba rakendatud
> Liquibase-migratsioonide kommentaarid** (`20260902-platform-api-key.sql`,
> `20261008-registry-sync.sql`, `20261009-drop-registry-tables.sql`) viitavad endiselt vanadele
> numbritele, sest nende failide baitе muutmine rikuks Liquibase'i checksum'id ja reegel on
> "muutumatuna püsiv master-ajalugu" (vt `AGENTS.md`). Need viited on ajaloolised; käesolev ADR on
> nende otsuste kehtiv sisu.

## Probleem

Register on **õiguslik/lepinguline artefakt**: liitumine on ametlik sündmus, mitte runtime'i
mutatsioon. Lubamatu muudatus või vigane sisend kahjustab otse:

- gate'i URL-i vahetus → signeeritud AS4 sõnum läheb ründaja endpoint'i (Chain-C);
- vigane gate'i rida → `edelivery` ei käivitu (F-EDEL-BOOTFAIL-1);
- vigane PEM → usaldusankru mürgitamine (F-EDEL-19);
- tühja `subsets`-iga asutus → subset-kontrollist möödumine (F-SQL-1);
- Class-2 vead kõigil CRUD-radadel (topeltkodeering, tüübivead, pikkuspiirangud jne).

Kui register on muudetav admin-REST-iga ühe admin-JWT-ga, saab igaüks, kellel on vastav õigus,
registri üle kirjutada. Muudatus peab jõustuma **release'iga**, mis jätab jälje: git-ajalugu,
ülevaatus, allkiri, deploy-logi.

## Otsus

1. **Allikas.** Register elab repos kataloogis `registry/`:
   - `registry/gates/<id>.yml`, `registry/platforms/<id>.yml`, `registry/authorities/<id>.yml`;
   - failinimi = `id`; sertifikaadid YAML-i literaalsete plokkidena (`|`), et need oleksid
     ülevaatusel loetavad;
   - kataloog ei asu `DSL/` all, sest iga selle esimese taseme kataloog on Ruuteri projekt ja
     failid loetaks marsruutidena;
   - puuduvad ja `null`-väärtusega valikulised võtmed **jäetakse genereeritud JSON-ist välja**;
     tühjad kollektsioonid jäävad (`subsets: []` tähendab "õiguseta asutus", `headers: {}`);
   - **platvormi `apiKey` on avatekst** (§5, muudab ADR-004-t).

2. **Allkiri — NÕUE, MIDA EI OLE VEEL RAKENDATUD.** Release'i käigus allkirjastatakse **manifest**,
   mis sisaldab nii **allikate** (`registry/**/*.yml`) kui ka **genereeritud artefaktide** (`*.json`)
   failipõhiseid SHA-256 hashe, pluss git-commit ja ajatempel. Avalik võti **ei tohi reisida koos
   allkirjaga** (vt "Kuidas allkirja rakendada" allpool). Kuni see on tegemata, kaitseb registrit
   ainult PR-i ülevaatus ja release-protsess, mitte krüptograafia.

3. **Tarne.** `scripts/registry-to-json.py` (PyYAML) on ühtlasi **konverter ja valideerija**: see
   kontrollib kohustuslikud võtmed, `id` vs failinimi, `status`-enumid, `countryCode`, PEM-kuju,
   `subsets` koodid, tundmatud võtmed ja duplikaatsed id-d. Konverteerimine käib **image'i
   build-ajal** (`docker/registry/Dockerfile`, kaheastmeline: `python:3.13-alpine` + PyYAML →
   `nginx:stable-alpine`). Serveerib **staatiline nginx** pordil 8080: `/<tüüp>.json`
   (kogu register massiivina), `/<tüüp>/<id>.json` ja `health.json`. Rakenduskoodi ei ole,
   runtime-mount'i ei ole, keskkonna-spetsiifiline register tähendab keskkonna-spetsiifilist
   image'it. **Fail-closed on build-ajal**: vigane fail ei lase image'it buildida. CI jooksutab
   sama konverterit (ilma `--out`-ita), seega vigane register kukub läbi ka ilma buildita.

4. **Andmebaasis ei ole registrit.** Tabelid `gates`, `platforms`, `authorities`, nende
   read-modelid (`rm_gates`/`rm_platforms`/`rm_authorities`) ja tüübid
   `gate_status`/`authority_status` **kustutati** (`20261009-drop-registry-tables.sql`).
   `protect_registry_append()` kehtib edasi ainult `users` tabelile. `read_model_pointer`,
   `read_model_generation_seq` ja `rm_consignment_*` jäävad — need on saadetiste omad (ADR-012),
   mitte registri.

5. **Platvormi võti on avatekst (muudab ADR-004-t).** Ruuteri avaldisemootoris **ei ole ühtegi
   räsimisfunktsiooni** (kontrollitud jooksva engine'i vastu: ei `crypto`, `crypto.subtle`,
   `TextEncoder` ega `atob`), seega ei saa guard SHA-256-te arvutada. `registry/platforms/<id>.yml`
   sisaldab `apiKey` avatekstina ja `platforms/.guard.yml` võrdleb `X-Api-Key`-d sellega otse,
   lubades ainult `ONLINE` platvormi. Tagajärg: **`registry/` ja sellest builditud image on
   saladuste hoidla** — porti ei avaldata kunagi, admin-vastused eemaldavad `apiKey`-i ja
   tagastavad tuletatud `hasApiKey`, guard eemaldab `apiKey`-i ka handlerile antavast
   `${platform}`-ist. Allkiri (§2) muudab võtme **võltsimise** tuvastatavaks, aga mitte
   **loetamatuks** — see on eraldi probleem.

6. **CRUD eemaldatakse.** Admin `POST`/`PUT`/`DELETE` gate'idele, platvormidele ja asutustele, koos
   alamroutedega (`ping`, `api-key`), ja nende ReSQL-failid on kustutatud. `GET`-id jäävad ja
   Admin GUI on **read-only vaade**, mis loeb sama allikat, mida runtime. `users` ja `consignments`
   säilitavad oma admin-kirjutusrajad.

7. **Kustutamine.** Tombstone'it ei ole: kataloogist kadunud fail on kustutatud kirje, päringud
   saavad 404 ja guardid keelduvad. Id (ja asutuse `registryCode`) on jälle vaba. Ajalugu annab
   **git**, mitte tabel. `authorities.status` kaob üldse — asutus on aktiivne sellepärast, et
   tema fail on olemas.

8. **Ruuteri konstandid — NÕUE, MIDA EI OLE VEEL RAKENDATUD.** Konstantides (`constants.ini`) peaks
   olema manifesti hash/versioon **drift-tuvastuseks**, mitte andmeallikana: kui iga keskkond
   buildib oma image'e, on "vale register on deploitud" reaalne operatsioonirisk ja odav
   tuvastada. (Ruuter ise ei saa hash-e arvutada — vt §5 — seega saab ta ainult **võrrelda**
   registri teenuse teatatud väärtust konstandiga, mitte seda ise tuletada.)

9. **Kasutajate CRUD jääb REST-iks** (ADR-002): kasutajad liituvad ja lahkuvad regulaarselt, see ei
   ole lepinguline artefakt.

## Kuidas allkirja rakendada

Neli otsust, millest esimene määrab, kas allkirjast on üldse kasu:

1. **Usaldusankur peab olema artefakti väliselt.** Kui avalik võti tuleb samast artefaktist, mida
   ta kaitseb, on kontroll iseennast tõestav ja kasutu. Valikud tugevuse järgi: klastri
   **admission-policy** (usaldusväärne CI-identiteet, nt Kyverno/Gatekeeper + Sigstore OIDC),
   **Kubernetes Secret / Vault `ExternalSecret`** (repo senine muster), või **Sigstore keyless**
   (võtit ei ole üldse — usaldus on Fulcio sert + Rekor logi).
2. **Mida allkirjastada.** Manifesti **baitе**, ja manifest kannab hash-e:
   `{version, gitCommit, created, sources:{path: sha256}, artifacts:{path: sha256}}`. Kontroll on
   siis kolm sõltumatut sammu: (a) allkiri üle saabunud manifesti baitide — nii ei ole vaja
   kanoniseerimist ega kanna PyYAML-i versioon; (b) allikate hashid arvutatakse puust uuesti;
   (c) konverter jooksutatakse ja selle väljundi hash-e võrreldakse `artifacts`-iga — **ilma (c)
   sammuta on konverter ise usaldusabelas** ja muudetud `registry-to-json.py` väljastaks
   allkirjastatud allikatest erineva JSON-i. Kasuta standardpakendit (DSSE / in-toto), mitte oma
   formaati; Ed25519, kui FIPS ei nõua ECDSA-P256-t.
3. **Kus võti elab.** Privaatvõti: HSM / Cloud KMS (`gcpkms://`, `awskms://`, Vault Transit) või
   riistvaratoken (füüsiline kohalolu); **CI-variablis ainult juhul, kui oht on eksimused, mitte
   kompromiteerimine** — kompromiteeritud pipeline allkirjastab kõik. Avalik võti: Secret-ist või
   admission-policy-st, **mitte** imagest. Rotatsioon: usalda **võtmete hulka** (key ID-de
   lubatud nimekiri), mitte ühte võtit.
4. **Millal verifitseerida.** Build-ajal (kiire tagasiside, aga nõuab väliselt pinningut),
   **deploy-ajal admission-policy-ga** (ainus kiht, mis tabab "keegi pushis asendusimage'i"),
   konteineri käivitusel init-konteineriga (taastab käivitusaegse fail-closed-i, mida §3 loobus)
   ja **tarbijapoolselt `edelivery`-s laadimisel** (JVM-is on krüpto olemas; Ruuteris ei ole —
   vt §5). Build tohib ainult **verifitseerida, mitte allkirjastada**.

## Põrjus

- Registri muudatus käib PR-i, ülevaatuse ja deploy kaudu — see on tahtlik omadus, mitte piirang.
- Üks tõeallikas: kaob git'i ja andmebaasi vaheline tuletatud koopia koos read-modelite,
  purge'ide, tombstone'ide ja idempotentsuse elutsükliga.
- Lugejad on HTTP-kliendid, seega muudatust ei ole vaja migreerida ega tagasi rullida.
- Vigane register ei jõua kunagi käivitusse (build-värav), mitte ainult ei logita.

## Tagajärjed ja hinnad

- **Register on päringu teel kriitiline sõltuvus:** `registry` ei vasta → platvormi- ega
  X-Road-päringud ei autori end ja `edelivery` ei saa party-registrit. `ruuter` ja `edelivery`
  sõltuvad teenuse tervisest.
- **Ruuter teeb rohkem tööd päringu kohta** (JSON-dokument + filter DSL-avaldisena, mitte
  indekseeritud SQL-päring). Registrid on väikesed, seega aktsepteeritav.
- **Platvormi võtmed on image'i kihtides** (ja seega image-registris, SBOM-is, trivy
  skaneeringutes). See on vastuolus reegliga "container image is never the carrier"
  (`identity-and-access/README.md`) — teadlik kompromiss, mida §2 allkiri ei leevenda.
- **Registri muudatus nõuab image'i uuesti buildimist**; `develop.watch` ei aita, sest mount'i ei ole.
- **Runtime-valideerimist ei ole** — kogu kontroll on build-ajal.
- **`/<tüüp>/<ID>.json` suurtähtedega annab 404.** Staatiline failiserver on tõstutundlik, aga id-d
  olid varem `CITEXT`. Seetõttu **DSL väiketähestab id enne päringut** (7 kohta), nii et `EU-EE`,
  `eu-ee` ja `Eu-Ee` käituvad ühtemoodi; dokumentides on `id` endiselt kanoonilises kujus.
- **Andmebaasi-poolne ajalugu kaob:** registrimuudatuse jälg on git, mitte `created_at/revision` read.

## Muudatused algse eelnõu suhtes

| Algne eelnõu (01.10.2026) | Tegelik (08.10.2026) |
|---|---|
| YAML + sertifikaadid eraldi PEM-failidena | YAML, sertifikaadid literaalsete plokkidena |
| Platvormi võtmest ainult hash | **Avatekst** `apiKey` (§5) |
| Init-konteiner laeb andmed append-only tabelitesse | Tabeleid ei ole; konverter build-ajal, nginx serveerib |
| `app` roll kaotab INSERT-õiguse | Moot — registritabeleid ei ole |
| Kataloogist kadunud kirje → tombstone | Moot — puudumine **on** kustutamine (§7) |
| Lugejad loevad samu tabeleid | Lugejad loevad HTTP kaudu (§8 muutus) |
| Konstantides manifesti hash drift-tuvastuseks | **Endiselt tegemata** (§8) |
| Manifest allkirjastatakse | **Endiselt tegemata** (§2) — peamine lahtine punkt |

## Lahtised küsimused

1. **Allkirjastamisskeem ja võtmehoid** (§2) — skeem, võtme asukoht ja usaldusankur; vt
   "Kuidas allkirja rakendada". Viide F-GC-3 disainile, mida selles repos ei ole.
2. **Drift-tuvastus** (§8) — kus manifesti hash konstandis elab ja kes seda võrdleb.
3. **API-võtmete väljastamine ja rotatsioon** ilma runtime-endpoint'ita: rotatsioon = commit +
   build + deploy. Kas ja millal viia võtmed registrist välja (Secret), et rotatsioon ei nõuaks
   uut release'i ja et "image ei ole saladuse kandja" reegel taastuks?
4. **Hotfix-release'i protsess** — lekkinud võtme või vigase kirje korral on ainus tee uus
   allkirjastatud release; vaja dokumenteeritud kiiret teed (nt kahe isiku reegel).

## Seotud

- [ADR-002](002-status-over-isactive.md) (kasutajate CRUD jääb REST-iks),
  [ADR-004](004-platform-api-key.md) (**muudetud §5-ga**: võti on avatekst, mitte SHA-256),
  [ADR-006](006-xroad-identity-and-subsets.md) (asutuse `subsets` on autoriõiguse allikas),
  [ADR-012](012-read-model-vs-query-level-denormalisation.md) (saadetiste read-modelid — need jäid alles)
- kemit-ee/efti-gate-ee#187 (A7), kemit-ee/efti-gate-ee#190 (A7-IMPL)
