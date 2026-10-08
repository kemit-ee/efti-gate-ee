# ADR-015: Register kui failiserver — registritabelid kustutatakse, `registry` teenus serveerib `registry/**`

**Otsus (08.10.2026, Anton Keks):** [ADR-014](014-registry-as-git-folder.md)-ga sisse viidud
"JSON → andmebaas → lugejad" vaheetapp **eemaldatakse**. Register jääb repos kataloogi `registry/`,
aga seda ei sünkroonita enam andmebaasi: uus **`registry` teenus** loeb ja valideerib kataloogi
käivitusel ning serveerib selle JSON-ina HTTP kaudu. Tabelid `gates`, `platforms`, `authorities`
ja nende read-modelid **kustutatakse**. Samuti **muudetakse ADR-004-t**: platvormi API-võti on
registris avatekstina, sest Ruuteri avaldisemootoris ei ole ühtegi räsimisfunktsiooni.

## Kontekst

ADR-014 tegi registrist deklaratiivse allika, aga jättis andmebaasi vahekihiks: ühekordne
`registry-sync` konteiner kirjutas JSON-id append-only tabelitesse ja kõik lugejad — Ruuteri
guardid, `edelivery` party-register, admin-vaated — lugesid endiselt SQL-ist. See hoidis lugejad
muutumatuna (ADR-014 "Lugejad ei muutu"), aga tähendas, et registri sisu on **kahes kohas**: git ja
andmebaas, kusjuures andmebaasi oma on tuletatud, versioonitud ja vajab oma elutsüklit
(read-modelid, purge, tombstone'id, taastamise erandid).

Sama otsust oli ADR-011 juba kaalunud ja **tagasi lükanud**: *"Registriteenus … Ei võitnud: guardid
ja registrit lugevad SQL-id tuleks asendada HTTP-kutsetega ning teenus muutub kriitiliseks
sõltuvuseks."* Käesolev ADR teeb teadlikult selle, mille ADR-011 tagasi lükkas — hinnang
andmebaasi vahekihi keerukuse ja teenuse lihtsuse vahel on muutunud.

## Otsus

1. **Allikas ei muutu.** `registry/{gates,platforms,authorities}/<id>.json`, failinimi = `id`,
   sertifikaadid inline PEM-ina. Vormingut muudetakse punktides 5–7.

2. **Uus teenus `registry`** (`docker/registry/`, python3 + standardteek, UID 65532) asendab
   `registry-sync`-i:
   - loeb ja **valideerib** kõik failid käivitusel (sama fail-closed reegel, mis varem: vigane fail
     peatab püsti tõusmise);
   - hoiab registrid mälus ja serveerib: `GET /<tüüp>.json` (kogu register ühe massiivina),
     `GET /<tüüp>/<id>.json` (üks kirje või 404), `GET /health`;
   - **ei filtreeri, ei autori ja ei otsi** — `status`-kontrollid, platvormi võtme võrdlus ja
     asutuse koodi mitmetähenduslikkuse reegel on Ruuteri DSL-is;
   - sisu laetakse **korra käivitusel**: faili muutmine rakendub `docker compose restart registry`
     järel (dev-keskkonnas `develop.watch` restart).

3. **Andmebaasi tabelid kustutatakse.** `gates`, `platforms`, `authorities`,
   `rm_gates`/`rm_platforms`/`rm_authorities`, tüübid `gate_status`/`authority_status`, ja kogu
   registri-spetsiifiline SQL. `protect_registry_append()` jääb kehtima **ainult `users`** jaoks.
   `read_model_pointer`, `read_model_generation_seq`, `rm_consignment_counts` ja
   `rm_consignment_summary` jäävad — saadetiste read-modelid ei ole register.
   `POST /ops/v1/refresh-read-models` katab edasi ainult saadetisi.

4. **Lugejad loevad HTTP kaudu.** Ruuter: `admin/GET/v1/{gates,platforms,authorities}` (nimekiri +
   üks), `gates/own`, `consignment-summary` (asutuse `subsets`), `dataset-local` ja
   `follow-up-local` (platvorm `baseUrl`/`headers`/`eDeliveryCert`), `authority/search` (ONLINE
   peer-gate'id), ning kaks guardi: `platforms/.guard.yml` (võti) ja `xroad/.guard.yml`
   (registryCode). Kotlin: `edelivery` loeb `/gates.json` ja `/platforms.json`
   (`RegistryClient`), jättes party-registri 60 s värskenduse samaks.
   **Ruuteril ei ole tsükleid**, seega nimekirja ja koodi-järgi otsingu jaoks on vaja
   kogu-registri dokumenti — see on punkti 2 `/<tüüp>.json` olemasolu põhjus.

5. **`authorities.status` kaob.** Asutus on aktiivne sellepärast, et tema fail on olemas; keelamine
   tähendab faili kustutamist. `gates`/`platforms` `status` jääb (`ONLINE`/`DISABLED`).

6. **`DELETED` kaob.** Kataloogist kadunud kirje on kustutatud, mitte tombstone. Kaovad:
   append-only versiooniajalugu, `last_ping_at`, "kustutatud id-d ei saa taaselustada" invariant ja
   DB-poolne auditijälg registrimuudatustest. Ajalugu annab nüüd **git**, mitte tabel.

7. **ADR-004 muutub: võti on avatekst.** Ruuteri avaldisemootoris ei ole `crypto`, `crypto.subtle`,
   `TextEncoder` ega `atob` (kontrollitud jooksva engine'i vastu), seega ei saa guard SHA-256-te
   arvutada. Seetõttu:
   - `registry/platforms/<id>.json` sisaldab `apiKey` **avattekstina**, mitte `apiKeyHash`-i;
   - `platforms/.guard.yml` võrdleb `X-Api-Key`-d sellega otse ja lubab ainult `ONLINE` platvormi;
   - `platforms` admin-vastused **eemaldavad `apiKey`** ja tagastavad tuletatud `hasApiKey`;
   - `$apiKey` eemaldatakse ka guardi poolt handlerile antavast `${platform}`-ist;
   - kataloog `registry/` on **saladuste hoidla**: `registry` porti ei avaldata kunagi (nagu
     ReSQL-i oma) ja Kubernetesesse tuleb see mount'ida Secret'ist, mitte ConfigMap'ist.

8. **Allkirjastatud manifesti ei tule** (nagu ADR-014-s). Usalduspiir on PR-ülevaatus + release.

## Põrjus

- **Üks tõeallikas.** Kaob git'i ja andmebaasi vaheline tuletatud koopia koos oma elutsükliga
  (read-model generatsioonid, purge, tombstone'id, `sync_*` idempotentsus, taastamise erand).
- **Vähem liikuvaid osi.** Üks teenus ja ~15 SQL-faili vähem; registri muudatus ei kirjuta
  andmebaasi ja ei vaja migratsiooni ega append-only semantikat.
- **Muudatus on nähtav kohe** ja seda saab lugeda samal kujul, nagu see repos on — sh
  sertifikaadid ja `subsets` — ilma SQL-projektsiooni vahekhita.

## Tagajärjed ja hinnad

- **Register on nüüd päringu teel kriitiline sõltuvus.** Kui `registry` ei vasta, ei autori
  platvormi- ega X-Road-päringud ennast (guardid loevad HTTP kaudu) ja `edelivery` ei saa party-
  registrit. Varem oli selleks andmebaas. Nii `ruuter` kui `edelivery` sõltuvad teenuse
  tervisest (`depends_on: service_healthy`).
- **Platvormi võtmed on repos ja HTTP-s avatekstina.** See on ADR-004 otsene tagasipööramine ja
  suurim üksikrisk selles otsuses: igaüks, kes pääseb `registry` teenuseni või reposse, näeb
  kõigi platvormide võtmeid. Leevendus on ainult võrguisolatsioon ja `registry/` kui Secret.
- **Ruuter teeb rohkem tööd päringu kohta.** Varem oli platvormi ja asutuse otsing indekseeritud
  SQL-päring; nüüd laetakse JSON-dokument ja filtreeritakse DSL-avaldisena iga päringu kohta.
  Registrid on väikesed (kümneid kirjeid), seega aktsepteeritav, aga see on teadlik vahetus.
- **Kaob andmebaasi-poolne ajalugu ja invariant.** Registrimuudatuse jälg on git, mitte
  `created_at/revision` read; "kustutatud id-d ei saa taaselustada" ei kehti enam (faili tagasi
  lisamine toob kirje tagasi) ja vana rea järgi ei saa enam öelda, milline oli registri seis
  hetkel X ilma vastavat commit'i vaatamata.
- **`gates.last_ping_at`, registri read-modelid ja `gate_status`/`authority_status` tüübid kaovad**
  koos tabelitega; `OFFLINE`-i ei ole enam kuskil.

## Rakendatav changeset

- `docker/registry/{serve.py,Dockerfile}` (uus; asendab `docker/registry-sync/`), `compose.yml`,
  `compose.override.yml`, `constants.ini`
- `DSL/Liquibase/changelog/20261009-drop-registry-tables.sql` (uus), `DSL/Liquibase/init.sql`
- Kustutatud: 15 registri ReSql-faili; `purge_read_model_generations.sql` ja
  `ops/POST/v1/refresh-read-models.yml` kärbitud
- Ruuter: `admin/GET/v1/{gates,gates/own,platforms,authorities,consignment-summary}.yml`,
  `platforms/.guard.yml`, `xroad/.guard.yml`, `xroad/GET/v1/subsets.yml`,
  `efti/POST/api/v1/{dataset-local,follow-up-local}.yml`, `efti/POST/api/v1/authority/search.yml`
- Kotlin: uus `RegistryClient` (`code/core`), `EDeliveryPartyRegistry` ümber lülitatud
- `code/ui`: `ruuterTypes.ts` ja platvormi/gate'i vaated vastavalt uutele väljadele
- `registry/README.md`, `registry/authorities/auth-xroad-deleted.json` kustutatud
- `tests/**`, `tests/sql/regression.py`

## Lahtised punktid

- **Devops.** `.gitlab-ci.yml` `builds:`/`components:` nimetuses on nüüd `registry` (mitte
  `registry-sync`); `services/efti/devops` vajab vastavat wrapper chart'i **ja `registry/` mount'i
  Secret'ist** — see on teises repos ega ole siin kontrollitav.
- **Allkiri ja `app`-i kirjutusõigus** — vt ADR-011/ADR-014 lahtised punktid; käesolev ADR ei
  muuda neid.
- **Dokumentatsiooni võlg** kasvab: eelmises ADR-014-s loetletud failid kirjeldavad nüüd ka
  registritabeleid ja `api_key_hash`-i, mis on samuti aegunud.
- **Võtmete rotatsioon** on commit + restart; runtime-endpoint'i ei ole (ADR-011 lahtine küsimus 2
  jääb lahtiseks).

## Seotud

- [ADR-014](014-registry-as-git-folder.md) — **asendatud**: register jääb git'i, aga andmebaasi
  vahekiht kaob
- [ADR-011](011-registries-as-signed-config.md) — käesolev ADR võtab kasutusele selle tagasi
  lükatud "registriteenuse" variandi
- [ADR-004](004-platform-api-key.md) — **muudetud**: võti on nüüd avatekst, mitte SHA-256
- [ADR-002](002-status-over-isactive.md), [ADR-006](006-xroad-identity-and-subsets.md),
  [ADR-012](012-read-model-vs-query-level-denormalisation.md)
