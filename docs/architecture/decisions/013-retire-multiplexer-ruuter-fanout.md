# ADR-013: Klite multiplexeri kaotamine — laialisaatmine ja seisund Ruuteri DSL-is

**Otsus (K4, 07.10.2026):** Klite `multiplexer` teenus **kustutatakse**. Selle sõnumite laialisaatmine
(identifikaatori-otsingu päring kõigile `ONLINE` peer-väravatele), vastuste agregeerimine ja
seisund viiakse Ruuteri DSL-i, kasutades Ruuter 0.11.0-rc primitiive `parallel_http`
(issue #135/#136) ja `detach` (issue #137) ning DB-põhist seisundit uues `search_results`
tabelis.

## Kontekst

`code/multiplexer` (Kotlin/klite) hoidis:
- mälus `Cache<UUID, PartyResponses>` (90 s TTL), mis registreeris levipäringu;
- `AppScope.async`-iga sõnumite laialisaatmine `edelivery /api/v1/send/:gateId` kaudu;
- `GET /api/v1/rest/:id` piiratud long-poll'i (30 s), mis **drainis** kogunenud XML-id ja
  liitis need ad-hoc stringina `⦀`-ga.

Ainus tarbija oli `DSL/Ruuter/efti/POST/api/v1/authority/search.yml`. Puudused: eraldi
JVM-protsess (512 MiB), mälus olev mitte-durable seisund, ühe-replika piirang, `⦀` string-liit
ja drain-semantika (pollija kaotas andmed, kui xml-mapper'i samm ebaõnnestus).

ADR-010 (authority-otsing ei blokeeru) jääb kehtima — muutub ainult **mehhanism**.

## Otsus

1. **Sõnumite laialisaatmine Ruuteris.** `authority/search.yml` esmakuts (kohalik möödalask) loeb
   `get_gates` kaudu `ONLINE` väravad (v.a oma), sisestab `search_results`-i `pending` rea ja
   käivitab `detach`-is `parallel_http` `aggregate: collect_all` sõnumite laialisaatmise
   `[#EDELIVERY_URL]/api/v1/send/${peer.id}`-le. Iga peer'i FTI021 XML teisendatakse
   `xml-mapper /search/response-to-json`-iga `ConsignmentRow[]`-iks ja kirjutatakse
   `complete` reana **JSONB**-na. Vastus on kohe `[]` + `x-poll-more: true`.
2. **Struktuurne agregeerimine.** Multimekseri `⦀` string-liite asendab `parallel_http`-i
   struktuurne `[{peer, response}]` massiiv. Poll loeb salvestatud JSONB rea — **idempotentne,
   ei draini** — seega mapper'i viga ei kaota enam andmeid.
3. **DB-põhine seisund.** Uus append-only `search_results` tabel
   (`search_id`, `status` = `pending|complete`, `body JSONB`, `created_at`). Esmakuts lisab
   `pending`, detach'itud ülesanne lisab `complete`; poll loeb uusima rea.
4. **Poll.** `X-Request-Id` + `X-Poll: true` korral loeb `authority/search.yml` uusima
   `search_results` rea ja magab+proovib uuesti (piiratud, ~30 s) kuni `pending` kaob.
   `complete` → salvestatud read + `x-poll-more: false`; tundmatu/purgetud id → `[]` +
   `x-poll-more: false` (nagu vana multimekser).
5. **Retention.** `search_results` on efemeerne; CronManager kutsub
   `POST /ops/v1/purge-search-results` (`keepMinutes` vaikimisi 10) iga 5 minuti järel.
6. **`async_responses`-i ei kasutata.** See tabel on AS4 vastuse 1:1 üleandmiseks
   (request_key kaupa claim/drain) ega sobi mitme-rea, mitte-draineerivaks otsingu seisundiks.

## Põhjus

- **Vähem liikuvaid osi.** Üks JVM-teenus ja üks võrguhüpe vähem; sõnumite laialisaatmine kasutab sama
  `edelivery`-t nagu varem, aga orkestreerib Ruuter, kes juba omab HTTP-orkestreerimist ja
  DB-seisundit.
- **Vastupidavus.** Mälus cache asemel Postgres: poll on idempotentne ja mitme replika korral
  ühine. Mapper'i viga polli ajal ei kaota tulemusi (varem drainis `/rest`).
- **Ühtne tööriist.** `parallel_http`/`detach` on üldotstarbelised primitiivid; sõnumite
  laialisaatmine on deklaratiivne DSL, mitte eraldi Kotlin-kood.

## Skeem

```
POST /efti/api/v1/authority/search   (esmakutse)
  └─ local_search → get_consignments
        ├─ ridu > 0 → respond_local               200, x-poll-more:false   [STOPP]
        └─ ridu = 0 → criteria_to_xml
                     → get_peer_gates (ONLINE, v.a oma)
                     → insert_search_pending       (search_results: pending)
                     → detach { parallel_http collect_all → edelivery /send/:gate
                                → parallel_http collect_all → xml-mapper response-to-json
                                → insert_search_complete (JSONB) }
                     → respond_pending             200, [], x-poll-more:true   [STOPP]

POST /efti/api/v1/authority/search   (X-Poll: true, keha {})
  └─ init_poll/attempts=0 → poll_read (get_search_result)
        ├─ complete  → respond_result              200, rows, x-poll-more:false
        ├─ 0 rida    → respond_unknown              200, [],   x-poll-more:false
        ├─ pending & attempts<60 → sleep 500ms → poll_read
        └─ pending & attempts=60 → respond_still_pending  200, [], x-poll-more:true
```

## Käitumisreeglid

- Esmakutse ei blokeeru kunagi peer-värava taga; sõnumite laialisaatmine on `detach`-itud.
- `x-poll-more` tähendus ja väärtused on identsed endise multimekseriga.
- Local-first jääb: kohalik tabamus ei käivita levipäringut (ADR-010 lahtine punkt jääb).
- Poll võib kesta kuni ~30 s (`attempts<60` × `sleep: 500`), mis mahub core 35 s / xroad 40 s
  timeout-ahelasse.
- `detach.max_inflight` (vaikimisi 256) kaitseb protsessi; ületäitumine → `detach` sammu viga →
  `broadcast_unavailable` 502.

## Teadaolev kompromiss

`parallel_http collect_all` ootab **kõiki** peer'e enne ühiskirjutust, seega osalisi tulemusi
polli kohta enam ei streamita (vana multimekser drainis järk-järgult). Pollid on see-eest
idempotentsed ja andmekadu-vabad. Vajadusel saab tulevikus lisada osalise kirjutuse.

## Rakendatav changeset

- `DSL/Ruuter/efti/POST/api/v1/authority/search.yml` — ümber kirjutatud (poll-loop + `detach`-itud sõnumite laialisaatmine)
- `DSL/Resql/efti/POST/{insert_search_pending,insert_search_complete,get_search_result,delete_expired_search_results}.sql`
- `DSL/Liquibase/changelog/20261007-search-results.sql`, `DSL/Liquibase/init.sql`
- `DSL/Ruuter/ops/POST/v1/purge-search-results.yml`, `docs/specs/deploy/cronmanager-archive.yaml`
- `code/multiplexer/` kustutatud; `code/settings.gradle.kts`, `docker/multiplexer/`,
  `compose.yml`, `compose.override.yml`, `constants.ini`, `tests/http-client.env.json`,
  `.gitlab-ci.yml` puhastatud
- `tests/http/runtime-components.http` — otsesed multimekseri testid eemaldatud;
  `DSL-tests/efti/authority-search-state.test.yml` lisatud; `tests/sql/regression.py` laiendatud

## Lahtised punktid

- **Osalised tulemused.** Vt "Teadaolev kompromiss".
- **Jaettu poll-võti.** `search_results` on `x-request-id`-põhine ilma omanikukontrollita
  (nagu varem); vt `x_road_integration.md`.
- **Devops-chart.** `multiplexer` tuleb eemaldada ka `efti` devops-repo chart'ist
  (`.gitlab-ci.yml` `components:` list on allikas).
