# ADR-010: Authority-otsing ei blokeeru; guard-dedup; DSL-tööriistad runtime-pildist

**Otsus (Sten Viljus, 10.09.2026):**

Jõudlusanalüüsi (`docs/askend_performance/analysis.md`) käigus tehtud kolm otsust,
mis muudavad `authority/search` voogu, guard-ahelat ja CI-d.

## Otsus

### 1. `authority/search` on "local-first, then broadcast" ega blokeeru üheski harus

- **Lokaalne tabamus** (`get_consignments` tagastab ridu) → vastus kohe,
  `x-poll-more: false`, teisi gate'e ei päritagi. See gate on oma konsignatsioonide
  osas autoriteetne.
- **Lokaalset vastet pole** → vastus kohe `[]` + `x-poll-more: true`, ja cross-gate
  broadcast käivitatakse **mitte-blokeerivalt** taustal.
- Teiste gate'ide tulemused kogub klient sama `X-Request-Id` + `X-Poll: true` + kehaga
  `{}` päringut korrates (`GET /multiplexer/api/v1/rest/:id`).
- Multiplexeri `POST /api/v1/first/:id` (blokeeris `poll(63s)`, timeout → `504`)
  **eemaldatakse**, asemele `POST /api/v1/search/:id` (registreerib + fännib
  `AppScope.async`-is, tagastab kohe).
- `GET /api/v1/rest/:id` on piiratud long-poll: ootab esimest gate-vastust kuni
  `MULTIPLEXER_REST_POLL_SECONDS` (vaikimisi 30 s), siis tagastab osalise tulemuse +
  `x-poll-more`. **Ei tagasta kunagi `500`-t ega `504`-t.**

### 2. Kattuvad guard'id ei jookse kaks korda

`efti/POST/api/v1/authority/.guard.yml` ja `efti/GET/api/v1/authority/.guard.yml`
saavad `declaration.override_ancestors: true`. Nende `X-Internal-Service-Token`
kontroll on ancestor-guardiga (`efti/{POST,GET}/api/v1/.guard.yml`) täpselt identne,
seega ancestori uuesti jooksutamine oli topelttöö igal `authority/*` päringul.

### 3. `dsl-lint` / `dsl-test` tulevad runtime-pildist

`docker/dsl-tools/Dockerfile` **kustutatakse**. Alates `turnerrainer/ruuter:0.9.14-rc`
(Ruuter #83) on mõlemad binaarid pildis (`/usr/local/bin/`), ehitatud täpselt sellest
mootoriversioonist, mille vastu nad linte teevad. CI (`.github/workflows/e2e.yml`,
`.gitlab-ci.yml`) jooksutab neid otse:
`docker run --rm -v "$PWD:/workdir" -w /workdir turnerrainer/ruuter:0.9.14-rc dsl-lint --dsl DSL/Ruuter …`

## Põhjus

**Blokeeriv esmavastus.** `forward_to_gates` tegi blokeeriva `POST /first`
(`timeout: 65000`); kui ükski peer-gate ei vastanud, ootas Ruuter 65 s ja
`stop_in_case_of_exception: true` tõttu andis geneerilise **HTTP 500** — 65 s pärast,
ilma ühegi kasuliku vastuseta. Lokaalne tulemus (mis oli juba käes) läks kaotsi.
Authority peab saama kohe teada "on olemas / ei ole olemas", mitte ootama iga
kaugvärava timeout'i.

**Lokaalne tabamus ei vaja broadcast'i.** Kui otsitav identifikaator on selle gate'i
konsignatsioonis olemas, on vastus autoriteetne kohe. Broadcast igal otsingul (ka
tabamusel) kolmekordistaks AS4-koormust ja lisaks kuumale teele kaks HTTP-hüpet
ilma sisulise võiduta.

**Topeltguard.** `authority/*` route'i katab kaks kattuvat kataloogi-guard'i, mille
`check_service_token` on baidilt identne. Jõudlusanalüüs (§4j) näitas, et guard-samm
on mõõdetavalt kallis (avaldise-hindamine per samm); sama kontrolli kaks korda
jooksutada on puhas raisk.

**dsl-tools pilt.** Ehitasime `dsl-lint` / `dsl-test` ise `docker/dsl-tools/`-ist
Ruuteri lähtekoodist, pinnitud runtime-tag'i külge. 0.9.14-rc teeb selle üleliigseks —
binaarid on pildis, versiooni-joondus garanteeritud.

## Skeem

```
POST /efti/api/v1/authority/search        (esmakutse, X-Request-Id, ilma X-Poll)
  └─ local_search  →  get_consignments
        ├─ ridu > 0  →  respond_local          200, x-poll-more: false        [STOPP]
        └─ ridu = 0  →  criteria_to_xml
                     →  start_broadcast  → POST /multiplexer/api/v1/search/:id  (202, mitte-blokeeriv)
                     →  respond_pending        200, [], x-poll-more: true      [STOPP]

POST /efti/api/v1/authority/search        (X-Poll: true, keha {})
  └─ poll_remaining  → GET /multiplexer/api/v1/rest/:id   (long-poll ≤ 30 s)
                     → rest_to_json → respond_rest         200, x-poll-more: <multiplexer>

multiplexer:
  POST /api/v1/search/:id   registreeri + AppScope.async { iga gate → edelivery /send }; tagasta kohe
  GET  /api/v1/rest/:id     drainuri saabunud vastused; kui tühi & pooleli → oota ≤ 30 s esimest
```

## Käitumisreeglid

- Esmakutse **ei blokeeru kunagi** peer-gate'i taga. Long-poll `/rest` on ainus
  ootav samm ja ka see on ülempiiriga (`MULTIPLEXER_REST_POLL_SECONDS`, vaikimisi 30 s)
  ning tagastab osalise tulemuse, mitte vea.
- Tundmatu `searchId` (või TTL-i ületanud) `/rest`-il → `[]` + `x-poll-more: false`.
- Lokaalse tabamuse korral `x-poll-more: false`; klient ei pea pollima.
- `xroad/POST/v1/search.yml`: `forward_to_core` timeout `70000 → 15000` (core ei
  blokeeru enam); `x-poll` päis kantakse kehast (`{"poll": true}`) core'i
  `check_poll`-ini, null-väärtus kukub http-sammus välja (Ruuter #57).
- `override_ancestors: true` mõjutab **ainult** `authority/` alampuud; `efti/api/v1/`
  muud route'id (`dataset-xml`, `follow-up-xml`, `consignments/search-xml`, `ping`)
  jooksevad ancestor-guardi endiselt.
- Route-kehas olev `template:` samm käivitab sihtmärgi guardi ikka (Ruuter #79 puudutab
  ainult guardi *sees* olevat `template:`-t), seega `-xml` / `authority/*` route'ide
  template-sammudel jääb `x-internal-service-token` edasi­saatmine nõutavaks.

## Rakendatav changeset

- `DSL/Ruuter/efti/POST/api/v1/authority/search.yml` — ümber kirjutatud (ülal skeem)
- `DSL/Ruuter/xroad/POST/v1/search.yml` — timeout + `x-poll` päis
- `DSL/Ruuter/efti/POST/api/v1/authority/.guard.yml`,
  `DSL/Ruuter/efti/GET/api/v1/authority/.guard.yml` — `override_ancestors: true`
- `code/multiplexer/src/MultiplexerRoutes.kt` — `/first` → `/search` + long-poll `/rest`
- `code/multiplexer/test/MultiplexerRoutesTest.kt`, `code/multiplexer/test.http`
- `tests/authority/authority-api.http` — uus kontrakt; `tests/admin/gates.http` —
  `EU-EE32` teardown
- `docker/dsl-tools/` — kustutatud; `.github/workflows/e2e.yml`, `.gitlab-ci.yml`,
  `AGENTS.md` — tööriistad `turnerrainer/ruuter:0.9.14-rc` pildist
- `docker/ruuter/Dockerfile`, `docker/ruuter-xroad-mock/Dockerfile` — `0.9.12-rc → 0.9.14-rc`

Verifitseeritud: `http-tests` 198/198, `dsl-lint` 74/74, `dsl-test` 9/9,
`multiplexer:test` roheline.

## Lahtised punktid

- **Broadcast ka lokaalse tabamuse korral.** Praegu lokaalne tabamus lõpetab voo.
  Kui reg 2020/1056 tõlgendus nõuab, et authority näeks sama identifikaatori kõiki
  konsignatsioone üle kõigi väravate ka siis, kui kohalik värav ühe leidis, tuleb
  `respond_local` asendada `start_broadcast + respond` (rida + `x-poll-more: true`).
  Eraldi otsus.
- **Ruuteri per-request overhead** (§6.7/§4j) — Ruuteri `http.post` hoiab juba
  ühenduse-poolile ReSql-i suunas (kontrollitud lähtekoodist); jääk on DSL-i
  avaldise-mootoris, mis on Ruuteri-poolne. Eraldi issue Ruuteri arendajatele.
