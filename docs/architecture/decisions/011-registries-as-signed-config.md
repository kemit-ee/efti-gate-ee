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

10. **Turvise usaldusvaade on registrist tuletatud (täiendus 20.10.2026, #316).** `registry/` jääb
    ainsaks tõeallikaks; eraldi käsitsi hallatavat peer-/trust-faili ei ole. Turvis (A9, krüpto
    `edelivery`-st välja) loeb ainult build-ajal genereeritud, allowlist-põhist
    `turvis-trust.json`-i ja **ei pääse** `registry` teenuse juurde. Üksikasjad: järgmine jaotis.

## Täiendus: Turvise usaldusvaade (A9, #316)

Kontekst: [Turvise integratsioonileping](../../specs/turvis/SPEC.md) §7.2–7.4 (PR #311, otsus kinnitatud
#304 merge-tingimusena 1). Käesolev jaotis fikseerib otsuse ADR-i tasemel; spetsifikatsioon kirjeldab
protokolli ja välju. Genereerimise rakendus on #315.

### Miks üks allikas

Kui Turvisel oleks oma käsitsi hallatav peer-nimekiri, oleks gate'i sertifikaat ja staatus kahes
kohas: rotatsioon või `DISABLED` unustataks ühes kohas ja usaldusotsus lahkneks vaikselt. Seetõttu:

- `registry/gates/*.yml` (§1) on **ainus** inimese muudetav peer-identiteedi, staatuse ja sertifikaadi
  allikas. Valikulised usaldusankrud (`registry/trust-anchors/*.pem`) on samuti selle puu osa.
- Turvise konfiguratsioon on **tuletis**, mitte teine allikas: `scripts/registry-to-json.py` (§3)
  väljastab lisaks registri JSON-ile `turvis-trust.json`-i samast puust, samas buildis, samal
  git-revisioonil.

### Miks Turvis ei loe `registry` teenust

`registry` teenus serveerib ka `platforms.json`-i **avateksti `apiKey`-dega** (§5). Turvis vajab
ainult gate'i identiteeti, staatust ja sertifikaati. Seepärast:

- usaldusvaade genereeritakse **allowlist'i** (mitte denylist'i) alusel: `party_id`, `status`,
  `verified_identity`, `cert_pem`, `cert_ski`, `cert_subject_dn`, `valid_until`, `trust_anchors[]`,
  `allowed_chain_anchors`. `apiKey`, `headers`, URL-id, `tlsCert` ja platvormid ei saa vaatesse
  sattuda; seda kontrollib test (#315);
- artefakt pakendatakse **eraldi** väikese staatilise image'ina (või volume'ina), mitte
  `apiKey`-e kandvas `registry` image'is;
- Turvise egress'il `registry` teenusesse **ei ole teed**; #309 NetworkPolicy ei pea seda avama ja
  kui artefakt toimetatakse volume'iga, ei ole Turvisel vaja ühtegi võrguteed.

### Suhe selle ADR-i varasemate otsustega

| Jaotis | Mõju |
|---|---|
| §1 allikad | Muutumatu: failid, mitte tabelid. Lisandub ainult valikuline `registry/trust-anchors/`. |
| §3 build-ajal tarne | Laieneb teise väljundiga (`turvis-trust.json`); sama konverter on endiselt valideerija. Build kukub, kui `ONLINE` partneril pole parsitavat `eDeliveryCert`-i, sertifikaat on aegunud, `verified_identity` kordub või väljund sisaldab allowlist'ist väljaspool olevat võtit. |
| §4 andmebaasis ei ole registrit | Muutumatu; usaldusvaade ei puuduta andmebaasi. |
| §5 avatekst `apiKey` | Muutumatu; just see on põhjus, miks Turvis `registry` teenust ei näe. |
| §2 allkiri, §8 manifesti hash | **Endiselt rakendamata nõuded.** Need on Phase 2 integriteedi kandidaadid, vt allpool. |

### Staatusemudel: deny wins

Registris on ainult `ONLINE` ja `DISABLED`. `DISABLED` kirjed jäävad Turvise vaatesse, et vastus
saaks nimetada staatust, mitte öelda "tundmatu". Partner on kasutatav ainult siis, kui **mõlemad**
vaated (`edelivery` registri vaade ja Turvise usaldusvaade) ütlevad `ONLINE`; kumbki pool ei tohi
teise otsust laiendada. Erinevate staatustega rida on **genereerimise mittevastavus**, mida lahendab
sobivate artefaktide deploy, mitte lubavama poole usaldamine. Tabel: SPEC §7.4.

### Kinnitamine (pinning), usaldusahel ja nähtavus

- **Vaikimisi pinning partneri kaupa**: tühi `allowed_chain_anchors` tähendab, et allkirjastaja
  sertifikaat peab võrduma registri `eDeliveryCert`-iga. See on Phase 1 vaikimisi tulemus.
- **Usaldusahel on partneri andmeväli**, mitte režiim koodis: `allowed_chain_anchors: [alias,…]`
  lülitab ühe partneri ankrutele, kui eFTI PKI haldus need annab; koodimuudatust ei vaja (SPEC §9.4).
- **Generatsioon on nähtav**: `generation` on kanoonilise sisu SHA-256; Turvis ja `edelivery`
  eksporteerivad selle metrikana ja Turvis tagastab `X-Turvis-Trust-Generation` päise. Alarm, kui
  replikate või Turvise ja `edelivery` generatsioonid erinevad kauem kui 5 minutit (SPEC §7.4).
- **Rike**: eelmine edukas snapshot jääb kasutusse; esmakäivitusel ilma snapshot'ita on Turvis
  mitte-ready ja vastab `TRUST_STORE_UNAVAILABLE` (SPEC §7.3).

### Tagajärjed

- Registri muudatus nõuab nüüd **mõlema** artefakti (`registry` ja usaldusvaade) uuesti buildimist ja
  deploy'd; `restart` ei rakenda midagi (jätkub §3).
- Sertifikaadi rotatsioon on üks commit: uus sertifikaat lisatakse samas muudatuses, vana eemaldatakse
  pärast partneri üleminekut (SPEC §7.4).
- Üks sama sisu hash annab mõlemale poolele võrreldava `generation`-i; erinevus on alarm, mitte
  eeldus.

### Phase 2 integriteet — LAHTINE

Phase 1: Turvis arvutab `generation`-i laadimisel uuesti ja keeldub faili hash'i mittevastavusest;
artefakt on seotud deploymentiga image digest'i kaudu. **See ei ole krüptograafiline allkiri.**

Phase 2 (eraldatud allkiri üle `generation`-i, kontrollitud seadistatud ankru vastu enne laadimist)
valik on **lahtine**: (a) selle ADR-i §2 release-aegne allkirjastamine (+ §8 manifesti hash) või
(b) A3 allkirjastatud manifest. Kumbagi ei ole repos rakendatud; see on nõue, mitte praegune kontroll.
Otsus tuleb teha enne, kui usaldusvaadet väidetakse krüptograafiliselt kaitstuks (jälgitav lahtise
küsimusena nr 5 allpool).

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
5. **Turvise usaldusvaate Phase 2 integriteet** — release-aegne allkiri (§2/§8) või A3 allkirjastatud
   manifest; vt "Täiendus: Turvise usaldusvaade". **Lahtine**, rakendamata.

## Seotud

- [Turvise integratsioonileping](../../specs/turvis/SPEC.md) §7.2–7.4 (usaldusvaade), kemit-ee/efti-gate-ee#304,
  #311, #315 (`turvis-trust.json` genereerimine), #316 (see täiendus), #309 (võrgupoliitika)

- [ADR-002](002-status-over-isactive.md) (kasutajate CRUD jääb REST-iks),
  [ADR-004](004-platform-api-key.md) (**muudetud §5-ga**: võti on avatekst, mitte SHA-256),
  [ADR-006](006-xroad-identity-and-subsets.md) (asutuse `subsets` on autoriõiguse allikas),
  [ADR-012](012-read-model-vs-query-level-denormalisation.md) (saadetiste read-modelid — need jäid alles)
- kemit-ee/efti-gate-ee#187 (A7), kemit-ee/efti-gate-ee#190 (A7-IMPL)
