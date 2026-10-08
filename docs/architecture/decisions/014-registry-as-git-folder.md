# ADR-014: Registrid git-kataloogist `registry/` — deklaratiivne sync ReSQL-i kaudu

**ASENDATUD (08.10.2026, Anton Keks) — vt [ADR-015](015-registry-as-file-server.md).** See ADR jääb ajalukku: registri allikas (`registry/**`) ja admin-kirjutusradade eemaldamine kehtivad endiselt, aga andmebaasi vahekiht (registritabelid, `sync_*`, read-modelid) kustutati ja register on nüüd failiserver. Loe punkte 2–4 ja 8 koos ADR-015-ga.

**Otsus (08.10.2026, Anton Keks):** [ADR-011](011-registries-as-signed-config.md) rakendatakse
**lihtsustatud kujul**: register elab repos kataloogis `registry/` **JSON-failidena** (üks fail iga
gate'i, platvormi ja asutuse kohta, failinimi = `id`), allkirjastatud manifesti ja eraldi
kirjutusrolli asemel laeb selle käivitusel andmebaasi **ühekordne `registry-sync` konteiner
ReSQL-i kaudu**. Admin-API kirjutusrajad ja Admin GUI muudatusvõimalused eemaldatakse.

## Kontekst

ADR-011 tuvastas, et gate'ide, platvormide ja asutuste register on **õiguslik/lepinguline artefakt**,
mitte runtime'i mutatsioon: gate'i URL-i vahetus suunab signeeritud AS4 sõnumi ründaja
endpoint'i, vigane PEM mürgitab usaldusankru, tühja `subsets`-iga asutus möödub subset-kontrollist.
Tabelid on append-only ja kehtib "viimane rida võidab", seega igaüks, kellel on registritabelisse
INSERT-õigus, saab registri üle kirjutada.

ADR-011 jäi **OTSUSTAMISEL**-olekusse ja selle mehhanism (allkirjastatud YAML-manifest, sertifikaadid
eraldi PEM-failidena, eraldi DB-roll, `app` kaotab INSERT-õiguse) eeldas allkirjastamisskeemi ja
võtmehoida, mida selles repos ei ole (vt ADR-011 "Avatud küsimused" 1).

## Otsus

1. **Allikas.** Register elab repos kataloogis `registry/`:
   - `registry/gates/<id>.json`, `registry/platforms/<id>.json`, `registry/authorities/<id>.json`;
   - failinimi (ilma `.json`-ita) **peab** võrduma `id`-väljaga;
   - sertifikaadid on **inline PEM-stringidena** JSON-is (üks kirje = üks iseseisev, ülevaatamisel
     täielikult kaetud fail);
   - platvormi API-võtmest ainult **SHA-256 hash** (`apiKeyHash`, 64 hex-märki), kunagi avatekst;
   - `status` kuulub täielikult registrile (`ONLINE`/`DISABLED`, asutusel `ACTIVE`/`DELETED`);
   - kataloog **ei asu** `DSL/` all, sest iga selle esimese taseme kataloog on Ruuteri projekt ja
     failid loetaks marsruutidena.

2. **Laadur.** Uus ühekordne Compose-teenus `registry-sync` (`docker/registry-sync/`) käivitub
   igal `docker compose up`-il:
   - loeb ja **valideerib** kõik failid (kohustuslikud väljad, `id` vs failinimi, `status`-enum,
     `countryCode` kuju, PEM-kuju, `apiKeyHash` hex-kuju, `subsets` koodid) — viga = fail;
   - saadab iga registri **ühe partiina** ReSQL-i: `POST /efti/sync_{gates,platforms,authorities}`;
   - värskendab admin-nimekirjade read-modelid (`POST /efti/refresh_registry_lists`);
   - **verifitseerib** tulemuse, lugedes registrid tagasi (`get_gates`/`get_platforms`/
     `get_authorities`) ja võrreldes id-de hulka ja staatustega;
   - iga viga lõpetab konteineri **nullist erineva koodiga**, ja `ruuter`/`edelivery` ootavad teda
     (`depends_on: service_completed_successfully`) — poolikult rakendatud registriga pinu üles ei tule.

3. **Semantika (append-only, deklaratiivne).** `sync_*` SQL:
   - uus kirje → INSERT;
   - muutunud kirje → **uus revisjon** (vana jääb ajalukku);
   - **muutumatu kirje → ei kirjutata midagi** (restart ei kasvata tabelit);
   - kataloogist **kadunud** kirje → `DELETED` tombstone;
   - **uuesti lisatud** fail → kirje taastub.

4. **Taastamise lubamine.** `protect_registry_append()`-i "Cannot reactivate deleted" piirang
   kehtib edasi ainult `users` tabelile
   (`DSL/Liquibase/changelog/20261008-registry-sync.sql`). Deklaratiivses mudelis on kirje
   tagasitoomine faili lisamisega **ootuspärane**, ja vana piirang muudaks seadusliku commiti
   käivitusveaks. Kasutajate CRUD jääb API-le (ADR-011 §9), seega nende piirang jääb alles.

5. **Ping eemaldatakse.** `status` on registri oma, seega ei ole enam ühtki runtime-kirjutajat:
   - `POST /admin/v1/gates/ping/:id`, `POST /admin/v1/platforms/ping/:id` kustutatud;
   - `POST /api/v1/admin/ping-gates` CronManager'i töö kirjeldus kustutatud;
   - `edelivery` sisemine `POST /api/v1/ping/:partyId` ja `EDeliveryClient.ping()` kustutatud
     (ainus kutsuja oli eelmainitud kaks DSL-marsruuti);
   - `last_ping_at` jääb skeemi (ajalooline veerg), aga jääb `NULL`.

6. **API-võti.** Platvormi `apiKeyHash` on registris; `POST /admin/v1/platforms/api-key/:id` ja
   `generate_platform_api_key.sql` on kustutatud. Rotatsioon = uus hash commit'i, avatekst antakse
   platvormi operaatorile kanaliväliselt. See vastab ADR-011 "Avatud küsimus" 2-le ilma
   runtime-endpoint'ita.

7. **CRUD eemaldamine.** Admin `POST`/`PUT`/`DELETE` gate'idele, platvormidele ja asutustele, koos
   alamroutedega (`ping`, `api-key`), ning nende ReSQL-failid
   (`insert_*`, `update_*`, `soft_delete_*`, `update_*_ping`, `generate_platform_api_key`)
   kustutatakse. `GET`-id jäävad ja Admin GUI muutub **read-only vaateks**. Marsruutide kadumise
   jälgitav tagajärg: kuna teed (`/admin/v1/gates`, `.../platforms`, `.../authorities`) on endiselt
   GET-i jaoks marsruuditud, vastab Ruuter kirjutusmeetodile **405 Method Not Allowed**, mitte 404 —
   ja kuna meetod ei sobi ühelegi marsruudile, guard ei käivitu, seega 401/403 asemel 405.
   See on kavatsetud leping: register on lugemiseks, mitte kirjutamiseks.

8. **Lugejad ei muutu.** Guardid (`platforms/`, `xroad/`), `edelivery` `ResqlClient`,
   K4 laialisaatmine ja SQL-id loevad endiselt samadest tabelitest. Muudatus on lugejate jaoks nähtamatu.

## Põrjus

- Register on versioonihallatav ja ülevaatamisel: iga muudatus on PR, mitte anonüümne REST-kutse.
- Üks allikas: kaob vahe admin-API, seed-migratsioonide ja runtime-mutatsioonide vahel.
- Fail-closed: vigane sisend peatab käivituse, mitte ei jõusta poolikut registrit.
- Lugejad jäävad samaks, seega muudatus on valdavalt **kustutamine**.

## Skeem

```
docker compose up
  database ─ liquibase ─ resql (healthy)
                  │
                  └─ registry-sync (one-shot, ./registry:ro)
                        ├─ valideeri registry/{gates,platforms,authorities}/*.json
                        ├─ POST /efti/sync_gates        ┐
                        ├─ POST /efti/sync_platforms    ├─ append-only, changed-only INSERT
                        ├─ POST /efti/sync_authorities  ┘
                        ├─ POST /efti/refresh_registry_lists
                        ├─ verify: get_gates / get_platforms / get_authorities
                        └─ exit 0 ─→ ruuter + edelivery käivituvad
                                     (exit ≠ 0 → pinu ei käivitu)
```

Tabeliseis ühe sync'i kohta:

| Registri fail | DB viimane rida | Tulemus |
|---|---|---|
| olemas | puudub | INSERT |
| olemas | sama sisu | ei midagi |
| olemas | erinev sisu | uus revisjon |
| puudub | `ONLINE`/`ACTIVE` | `DELETED` tombstone |
| puudub | `DELETED` | ei midagi |
| olemas | `DELETED` | uus revisjon (taastamine) |

## Käitumisreeglid

- `sync_*` on idempotentne: kordussync annab `upserted=0` ega kasvata tabelit.
- `registry/platforms` fail, mis `apiKeyHash`-i **ei sisalda**, jätab olemasoleva võtme puutumata ega
  loeta muutunuks (muidu lisaks iga restart uue revisjoni). Muutunud hash = rotatsioon ja saab
  `api_key_generated_at = now()`; `api_key_hint` tuletatakse hashi esimesest 8 hex-märgist.
- `sync_authorities` normaliseerib `subsets` (unikaalsed, kasvavas järjekorras) nii võrdluses kui
  INSERT-is — sama hulga ümberjärjestamine ei ole muudatus.
- Sertifikaadid võrreldakse bait-baidilt: puuduv reavahetus faili lõpus on päris muudatus.
- Gudide ja autentimise lugemisteed ei muutu; read-modelid (`rm_*`) katavad ainult admin-nimekirju.

## Erandid ADR-011-st

- **Allkirjastatud manifest jääb tegemata.** ADR-011 §2 nõudis manifesti allkirja ja avalikku võtit
  image'is. Selles etapis on usalduspiir PR-ülevaatus + release-protsess, mitte krüptograafiline
  allkiri. See on teadlik edasilükkamine (võtmehoid puudub), mitte unustatud nõue.
- **`app` roll ei kaota INSERT-õigust** (ADR-011 §4). Sync käib ReSQL-i kaudu, mis tootmises
  ühendub `efti_rw`-na (kuulub `app`-i). Eraldi kirjutusroll eeldaks teist ReSQL-i andmeallikat ja
  parooli (nagu `archive`). Kuna kõik muud registri kirjutusrajad on kustutatud, on järelejäänud
  pind täpselt `sync_*` endpoint'id ReSQL-is, mis on kättesaadavad ainult sisemises võrgus
  (avaldatud porti ei ole). Eraldi roll jääb võimalikuks kõvenduseks.
- **YAML → JSON, eraldi PEM-failid → inline PEM.** JSON on valitud, sest seda saab valideerida ja
  parsida ilma lisasõltuvusteta ning `jsonb_to_recordset` võtab selle otse vastu.

## Rakendatav changeset

- `registry/**` (uus) + `registry/README.md`
- `docker/registry-sync/{Dockerfile,sync.py}` (uus), `compose.yml`
- `DSL/Resql/efti/POST/sync_{gates,platforms,authorities}.sql` (uus)
- `DSL/Liquibase/changelog/20261008-registry-sync.sql`, `DSL/Liquibase/init.sql`
- Kustutatud: admin `POST`/`PUT`/`DELETE/v1/{gates,platforms,authorities}.yml`,
  `POST/v1/{gates,platforms}/ping.yml`, `POST/v1/platforms/api-key.yml`,
  `DSL/Resql/efti/POST/{insert,update,soft_delete}_{gate,platform,authority}.sql`,
  `update_{gate,platform}_ping.sql`, `generate_platform_api_key.sql`,
  `DSL/Liquibase/changelog/20260901-seed-own-gate.sql`,
  `DSL/Liquibase/changelog/20260814-mock-platform.sql`
- `code/edelivery`: `InternalRoutes.ping`, `EDeliveryClient.ping` ja nende test kustutatud
- `code/ui`: `pages/admin/{gates,platforms,authorities}/*` read-only, `ApiKeyModal.svelte` kustutatud
- `tests/admin/*.http` → read-only + "kirjutusrada ei ole marsruuditud (404)",
  `tests/authority/*.http` → fixture'id registrist, `tests/sql/regression.py` → `sync_*` testid

## Lahtised punktid

- **Allkiri.** Kui DB sisu usaldusväärsus (vt ADR-011 "Tagajärjed") osutub ebapiisavaks, tuleb
  tagasi tulla manifesti allkirja ja `app`-i kirjutusõiguse äravõtmise juurde.
- **Hotfix-tee.** Lekkinud platvormi võtme korral on ainus tee commit + deploy; kiiremat teed ei ole.
- **Suuruspiirang.** Üks partii = üks ReSQL päring, millel on `server.max_body_bytes` (1 MiB).
- **Registry failide hulk on `context:dev`-seedide asemel.** Dev/CI fixture'id (EU-EE, EU-MOCK,
  `mock`, `mock-edelivery`, `auth-*`) on committitud; tootmine mountib oma registri, samamoodi nagu
  `constants.ini` puhul.
- **Devops.** `.gitlab-ci.yml` sai `registry-sync` nii `builds:` kui `components:` nimekirja.
  `components:` peegeldab `services/efti/devops` wrapper chart'e — sinna on vaja vastavat
  `registry-sync` komponenti (ja `registry/` mount'i, nt ConfigMap/Secret või image'i sisse
  ehitatud kataloog), muidu ei laadita registrit Kubernetesesse. See on **teises repos** ega ole
  siin kontrollitav.
- **Dokumentatsiooni võlg.** Käesolev ADR ja `docs/architecture/registry-management/*` on uuendatud,
  aga need specifikatsioonid kirjeldavad endiselt eemaldatud kirjutusra discipline ja vajavad
  järgnevat ülevaatamist: `docs/cfr/registry-management/{gate,platform,authority}_registry.md`
  (EPIC 6/7/8 AC-d), `docs/specs/api_endpoints.md`, `docs/specs/openapi.yaml`,
  `docs/specs/permissions-matrix.md`, `docs/specs/errors.json`, `docs/specs/logging-spec.md`,
  `docs/specs/diagrams/*` (seq-09/10/11/15, state-03/04/05), `docs/specs/db/*`,
  `docs/architecture/Architecture-Baseline.md`,
  `docs/architecture/identity-and-access/user_management_and_rbac.md`,
  `docs/specs/deploy/resource-and-secrets-requirements.md`, `acceptance_test_preparation.md`,
  `PROJECT-OVERVIEW.md`. Need ei ole automaatselt valideeritud, seega ei kukuta CI-d läbi.

## Seotud

- [ADR-011](011-registries-as-signed-config.md) — käesolev ADR rakendab selle lihtsustatud kujul
- [ADR-002](002-status-over-isactive.md), [ADR-004](004-platform-api-key.md),
  [ADR-006](006-xroad-identity-and-subsets.md), [ADR-012](012-read-model-vs-query-level-denormalisation.md)
- kemit-ee/efti-gate-ee#187, kemit-ee/efti-gate-ee#190
