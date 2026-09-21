# Tehnoloogia ülevaade

Sellel lehel on ülevaade infosüsteemis kasutatavatest peamistest tehnoloogiatest ja komponentidest ning nende versioonidest. Versioonimuudatused säilitatakse versiooniajaloos.

Seis kirjeldab repo `dev`-i runtime-uuendust PR #153 ja PR #155 muudatusi, ning 2026-09-21 Ruuteri/ReSQL-i/TIM-i patch-versiooni bump'i. Git-kuupäev tähendab versiooni määrangu lisamist lähtekoodi, mitte tõendatud paigaldust DEV/PROD keskkonda. Deployment release-manifestid on eraldi devops-repos; nende keskkondade tegelikke versioone siin ei oletata.

## Hetkel kasutusel olevad tehnoloogiad ja komponendid

| Tehnoloogia / komponent | Otstarve | Versioon | Versiooni allikas | Kasutusel alates | Märkused |
|---|---|---|---|---|---|
| Ruuter | HTTP gateway ja YAML DSL | 0.10.1-rc | `docker/ruuter/Dockerfile`, `turnerrainer/ruuter:0.10.1-rc` | 2026-09-21 | Ka standalone X-Tee mock kasutab sama runtime'i. Lisab graceful SIGTERM shutdown'i ja multipart-piirid (mitteoluline, kuna ükski DSL route multipart-keha ei aktsepteeri) |
| ReSQL | SQL endpointide HTTP executor | 0.4.3-alpha | `docker/resql/Dockerfile`, `turnerrainer/resql:0.4.3-alpha` | 2026-09-21 | Distroless UID 65532, sisemine trust-network; CLI health-probe. Puhtalt lisanduv bump (Traceparent header, graceful pool-close) |
| TIM | Token & Identity Manager | 0.4.1-alpha | `docker/tim/Dockerfile`, `turnerrainer/tim:0.4.1-alpha` | 2026-09-21 | Ruuteri introspection-klient autentitakse eraldi saladusega. **Image on nüüd distroless** (UID 65532) — CA-usaldus ja JWT-võtme genereerimine kolisid eraldi `tim-init` konteinerisse (`docker/tim-init/`), mis kirjutab TARA-Mock'i self-signed CA `SSL_CERT_FILE` kaudu ja genereerib RSA-võtme jagatud volume'itesse enne `tim` teenuse käivitumist |
| PostgreSQL | Gate'i andmebaas | 18 (patch määramata) | `compose.yml`, `postgres:18`, `748f99e` | 2026-08-03 | Muutuv major-tag; runtime app-rollil SELECT/INSERT |
| PostgreSQL | TIM-i eraldi andmebaas | 18 (patch määramata) | `compose.yml`, `postgres:18`, `ed49344` | 2026-08-25 | Eraldi andmebaas ja volume |
| JVM | Kotlin teenuste build/runtime | 25 (patch määramata) | `docker/code/Dockerfile`, `eclipse-temurin:25-alpine`, `25-jre-alpine` | 2026-08-03 | eDelivery, xml-mapper, multiplexer, pubsub |
| Kotlin | JVM teenuste keel | 2.4.20 | `code/build.gradle.kts`, commit `ea7bdcb` | 2026-09-08 | JVM toolchain 25 |
| Klite | Kotlin HTTP/XML/JSON/JDBC raamistik | commit 63ae3830 | `code/build.gradle.kts`, JitPack koordinaadid | 2026-09-10 | Commit-pin, mitte oletatud release-number |
| Svelte | Admin UI | 5.56.4 | `code/ui/package-lock.json`, `82fb588` | 2026-08-11 | `package.json` sisaldab semver-vahemikku |
| Vite | UI build | 7.3.6 | `code/ui/package-lock.json`, `82fb588` | 2026-08-11 | Build töötab Node 22 image'is |
| Node.js | UI build-runtime | 22 (patch määramata) | `docker/ui/Dockerfile`, `node:22-alpine` | 2026-08-13 | Build-stage |
| Nginx | UI ja reverse proxy | määramata | `docker/ui/Dockerfile`, `nginx:stable-alpine` | 2026-08-13 | Muutuv tag; patch tuleb kontrollida paigaldatava image'i digestist/runtime'ist |
| Liquibase | Skeemi migratsioonid | 4.29.2 | `docker/liquibase/Dockerfile`, `liquibase/liquibase:4.29.2` | 2026-08-03 | Master-changelog töötab nii värskel kui olemasoleval installil |
| TARA mock | OIDC arendusteenus | määramata | `docker/tara-mock/Dockerfile`, `golang:latest`, `debian:bookworm-slim` | määramata | Go builder ja Debian runtime; need ei tõenda mocki eraldi release-versiooni |
| CronManager | Ajastatud tööde (archive sweep jm) väline väljakutsuja | v0.2.2-alpha | [`turnerrainer/cronmanager`](https://github.com/turnerrainer/cronmanager), `docs/specs/deploy/cronmanager-archive.yaml` | 2026-09-21 | Ei ehitata/käivitata sellest repost — operaatori eraldi teenus. Varem ekslikult viidatud kui `Buerostack/CronManager`. HTTP-töö tüübil pole `headers:`/`body:` tuge ega käitusaegset `${...}` interpoleerimist — opsToken käib query-param'ina (`DSL/Ruuter/ops/.guard.yml`), mitte Bearer-päisena |

## Versiooniajalugu

| Tehnoloogia / komponent | Versioon | Kasutusel alates | Kasutusel kuni | Allikas või muudatus |
|---|---|---|---|---|
| Ruuter | 0.9.15-rc | 2026-09-10 | 2026-09-14 | `7d6c5d3`; asendatud runtime-PR #153 commit'is `c678bc5` |
| Ruuter | 0.10.0-rc | 2026-09-14 | 2026-09-21 | `c678bc5`, mõlemad Ruuteri Dockerfile'id |
| Ruuter | 0.10.1-rc | 2026-09-21 | | Patch-bump, mõlemad Ruuteri Dockerfile'id + `.github/workflows/e2e.yml` + `.gitlab-ci.yml` |
| ReSQL | 0.2.0-alpha | 2026-09-07 | 2026-09-14 | `ff56a3c`; PR #153 eelne `docker/resql/Dockerfile` |
| ReSQL | 0.4.2-alpha | 2026-09-14 | 2026-09-21 | `c678bc5` |
| ReSQL | 0.4.3-alpha | 2026-09-21 | | Patch-bump |
| TIM | 0.3.0-alpha | 2026-09-07 | 2026-09-14 | `c119fa9` |
| TIM | 0.4.0-alpha | 2026-09-14 | 2026-09-21 | `c678bc5` |
| TIM | 0.4.1-alpha | 2026-09-21 | | Distroless base — `docker/tim/Dockerfile` rewritten, uus `docker/tim-init/` sidecar, `compose.yml` healthcheck ja `depends_on` muudetud |

## Runtime-uuenduse ja PR #155 mõju

Ruuteri inbound timeout on 90 sekundit, et G2G timeout-ahel mahuks ära. XML-i vastused edastatakse toore XML-ina õige Content-Type'iga. ReSQL non-2xx staatust kontrollitakse enne tühja massiivi käsitlemist puuduva kirjena; DB-rikke readiness on 503.

Autentimine on fail-closed: puuduv credential annab 401, vigane/mitmene identiteedivastus ei autentida. Dev-login on image'i vaikeseadistuses keelatud ja lubatakse arenduse/CI build'is eraldi. Platform-upload kontrollib UIL-i omanikku; X-Road request-ID peab olema hex UUID.

Append-only latest-valik kasutab `(created_at, revision)`. Viiele põhitabelile lisatakse identity-revision ja latest-indeksid; nelja registri INSERT-id võtavad logical-ID järgi advisory transaction lock'i. Kasutaja revocation ja mitteaktiivsus säilivad ning stale INSERT ei taasta kustutatud registrikirjet ega vanemat API-võtit.

`DSL/Liquibase/init.sql` on tühja andmebaasi koondatud skeem ilma dev-seedita; master-changelog säilitab olemasolevate installide migratsiooniajaloo. Mõlemat skeemiteed kontrollivad samad 27 SQL-regressiooni. Acceptance'i tulemused ja juhised asuvad `acceptance_test_preparation.md` failis.

Confluence'i olemasolevat versiooniajalugu tuleb sünkroonimisel säilitada; see fail ei asenda seal varem talletatud ajalookirjeid.

## 2026-09-21 patch-bump: Ruuter, ReSQL, TIM

Ruuter 0.10.0-rc → 0.10.1-rc ja ReSQL 0.4.2-alpha → 0.4.3-alpha on tavapärased patch-bump'id ilma juhtme peal nähtava käitumise muutuseta, mis meid puudutaks (vt versioonitabeli märkused). Mõlema `dsl-lint`/`dsl-test` (kohalikult käivitatud vastu uut Ruuteri image't) ja täisstacki tervisekontroll läbisid muudatusteta.

TIM 0.4.0-alpha → 0.4.1-alpha on suurem muudatus: TIM-i image on nüüd **distroless** (`gcr.io/distroless/cc-debian12:nonroot`, ReSQL-iga sama UID 65532), millel pole shelli, `apt`-i ega `tini`-t. Varasem `docker/tim/entrypoint.sh` tegi kolm asja käivitumisel — ootas TARA-Mock'i self-signed CA-sertifikaati, importis selle OS-i usaldusjuurde (`update-ca-certificates`) ja genereeris RSA JWT-allkirjastusvõtme — millest ükski distroless-image'is enam ei tööta.

Lahendus: uus `tim-init` teenus (`docker/tim-init/`, tavaline `debian:bookworm-slim` põhine image koos `openssl`-iga) teeb kõik kolm sammu jagatud volume'itesse **enne** kui `tim` teenus üldse käivitub (`compose.yml`: `tim` `depends_on: tim-init: condition: service_completed_successfully`). CA-usaldus lahendatakse `update-ca-certificates` asemel `SSL_CERT_FILE` keskkonnamuutujaga, mis osutab `tim-init`-i kirjutatud bundle-failile — see toimib, sest reqwest/rustls-native-certs loevad seda muutujat otse (openssl-probe konventsioon), ilma OS-i paketihalduseta. Healthcheck kasutab TIM-i enda `tim healthcheck` alamkäsku `curl` asemel, samamoodi nagu ReSQL juba kasutab `/app/resql health`.

Kohapeal valideeritud täisstacki käivitusega: `tim-init` kirjutab CA bundle'i ja võtme, `tim` laeb võtme, käivitub ja vastab `healthy`, ning `GET /auth/login/tara` tõi reaalselt TARA-Mock'i OIDC discovery dokumendi HTTPS üle kätte (st CA-usaldus töötab tegelikkuses, mitte ainult teoreetiliselt). Testiti ka olemasoleva (vana entrypoint'iga loodud, teise UID omanikuga) `tim-jwt-key` volume'i peal — `tim-init` parandab omandi/õigused iga käivituse juures, mitte ainult võtme esmasel loomisel, nii et olemasolevatelt keskkondadelt uuendamine ei jää katki kinni.

## 2026-09-21 CronManager parandus

Sama päeva jooksul selgus, et dokumentides läbivalt viidatud `Buerostack/CronManager` on vale allikas — reaalselt kasutusel on [`turnerrainer/cronmanager`](https://github.com/turnerrainer/cronmanager) (v0.2.2-alpha), sama tootja Rust-taasteostus, mis kuulub samasse "h2ck.me v1" auditi-tsükli perre nagu Ruuter/ReSQL/TIM. See ei ole selle repo poolt ehitatav/käivitatav (operaatori eraldi teenus), aga selle tegelik DSL-skeem erineb sellest, mida meie `docs/specs/deploy/cronmanager-*.yaml` failid ja `DSL/Ruuter/ops/**` marsruudid eeldasid:

- HTTP-töö tüübil pole `headers:` ega `body:` tuge (bare `method`+`url`), ega käitusaegset `${...}` muutuja-interpoleerimist üheski väljas — `serde(deny_unknown_fields)` lükkab kogu töö-faili laadimisel tagasi, kui mõni väli pole tuntud.
- See tegi meie senise disaini (`Authorization: Bearer ${ARCHIVE_OPS_TOKEN}` päisena, JSON `body:`) täiesti võimatuks. Parandus: `DSL/Ruuter/ops/.guard.yml` kontrollib nüüd `?opsToken=` **query-parameetrit**, mitte päist; `archive-consignments.yml`/`purge-archive.yml` loevad parameetrid `incoming.params.*` kaudu, mitte `incoming.body.*`.
- `${GATE_BASE_URL}`/`${ARCHIVE_OPS_TOKEN}` platsihoidjad töö-YAML-ides on jätkuvalt ainult deploy-aegse renderdamis-sammu (Helm/Kustomize/CI) jaoks — CronManager ise neid kunagi ei asenda; see täpsustus lisati ka faili kommentaari, kuna varasem sõnastus jättis mulje, nagu CronManager teeks selle ise.
- `cronmanager-ping-gates.yaml` ja `cronmanager-expire.yaml` viitavad marsruutidele (`/ops/v1/ping-gates`, `/ops/v1/expire-identifiers`), mida `DSL/Ruuter/ops/` all veel ei eksisteeri — märgistatud failides selgelt `*** NOT YET IMPLEMENTED ***`, eraldiseisev lünk sellest versiooniparandusest.

`docs/specs/permissions-matrix.md` said uue "Changed in 1.7" kirje samas vaimus, kuid faili põhitabelid (Bearer-põhine kirjeldus, `/api/v1/admin/*` teed) jäid laiemas ulatuses üle vaatamata — see on eraldiseisev dokumentatsiooni-võla teema.
