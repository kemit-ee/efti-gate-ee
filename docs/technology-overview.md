# Tehnoloogia ülevaade

Sellel lehel on ülevaade infosüsteemis kasutatavatest peamistest tehnoloogiatest ja komponentidest ning nende versioonidest. Versioonimuudatused säilitatakse versiooniajaloos.

Seis kirjeldab repo `dev`-i runtime-uuendust PR #153 ja PR #155 muudatusi. Git-kuupäev tähendab versiooni määrangu lisamist lähtekoodi, mitte tõendatud paigaldust DEV/PROD keskkonda. Deployment release-manifestid on eraldi devops-repos; nende keskkondade tegelikke versioone siin ei oletata.

## Hetkel kasutusel olevad tehnoloogiad ja komponendid

| Tehnoloogia / komponent | Otstarve | Versioon | Versiooni allikas | Kasutusel alates | Märkused |
|---|---|---|---|---|---|
| Ruuter | HTTP gateway ja YAML DSL | 0.10.0-rc | `docker/ruuter/Dockerfile`, `turnerrainer/ruuter:0.10.0-rc` | 2026-09-14 | Ka standalone X-Tee mock kasutab sama runtime'i |
| ReSQL | SQL endpointide HTTP executor | 0.4.2-alpha | `docker/resql/Dockerfile`, `turnerrainer/resql:0.4.2-alpha` | 2026-09-14 | Distroless UID 65532, sisemine trust-network; CLI health-probe |
| TIM | Token & Identity Manager | 0.4.0-alpha | `docker/tim/Dockerfile`, `turnerrainer/tim:0.4.0-alpha` | 2026-09-14 | Ruuteri introspection-klient autentitakse eraldi saladusega |
| PostgreSQL | Gate'i ja TIM-i eraldi andmebaasid | 18 (patch määramata) | `compose.yml`, `postgres:18` | määramata | Muutuv major-tag; runtime app-rollil SELECT/INSERT |
| JVM | Kotlin teenuste build/runtime | 25 (patch määramata) | `docker/code/Dockerfile`, `eclipse-temurin:25-alpine`, `25-jre-alpine` | 2026-08-03 | eDelivery, xml-mapper, multiplexer, pubsub |
| Kotlin | JVM teenuste keel | 2.4.20 | `code/build.gradle.kts`, commit `ea7bdcb` | 2026-09-08 | JVM toolchain 25 |
| Klite | Kotlin HTTP/XML/JSON/JDBC raamistik | commit 63ae3830 | `code/build.gradle.kts`, JitPack koordinaadid | 2026-09-10 | Commit-pin, mitte oletatud release-number |
| Svelte | Admin UI | 5.56.4 | `code/ui/package-lock.json` | määramata | `package.json` sisaldab semver-vahemikku |
| Vite | UI build | 7.3.6 | `code/ui/package-lock.json` | määramata | Build töötab Node 22 image'is |
| Node.js | UI build-runtime | 22 (patch määramata) | `docker/ui/Dockerfile`, `node:22-alpine` | 2026-08-13 | Build-stage |
| Nginx | UI ja reverse proxy | määramata | `docker/ui/Dockerfile`, `nginx:stable-alpine` | 2026-08-13 | Muutuv tag; patch tuleb kontrollida paigaldatava image'i digestist/runtime'ist |
| Liquibase | Skeemi migratsioonid | 4.29.2 | `docker/liquibase/Dockerfile`, `liquibase/liquibase:4.29.2` | 2026-08-03 | Master-changelog töötab nii värskel kui olemasoleval installil |
| TARA mock | OIDC arendusteenus | määramata | `docker/tara-mock/Dockerfile`, `golang:latest`, `debian:bookworm-slim` | määramata | Go builder ja Debian runtime; need ei tõenda mocki eraldi release-versiooni |

## Versiooniajalugu

| Tehnoloogia / komponent | Versioon | Kasutusel alates | Kasutusel kuni | Allikas või muudatus |
|---|---|---|---|---|
| Ruuter | 0.9.15-rc | 2026-09-10 | 2026-09-14 | `7d6c5d3`; asendatud runtime-PR #153 commit'is `c678bc5` |
| Ruuter | 0.10.0-rc | 2026-09-14 | | `c678bc5`, mõlemad Ruuteri Dockerfile'id |
| ReSQL | 0.2.0-alpha | määramata | 2026-09-14 | PR #153 eelne `docker/resql/Dockerfile`; alus `15fade8` |
| ReSQL | 0.4.2-alpha | 2026-09-14 | | `c678bc5` |
| TIM | 0.3.0-alpha | 2026-09-07 | 2026-09-14 | `c119fa9` |
| TIM | 0.4.0-alpha | 2026-09-14 | | `c678bc5` |

## Runtime-uuenduse ja PR #155 mõju

Ruuteri inbound timeout on 90 sekundit, et G2G timeout-ahel mahuks ära. XML-i vastused edastatakse toore XML-ina õige Content-Type'iga. ReSQL non-2xx staatust kontrollitakse enne tühja massiivi käsitlemist puuduva kirjena; DB-rikke readiness on 503.

Autentimine on fail-closed: puuduv credential annab 401, vigane/mitmene identiteedivastus ei autentida. Dev-login on image'i vaikeseadistuses keelatud ja lubatakse arenduse/CI build'is eraldi. Platform-upload kontrollib UIL-i omanikku; X-Road request-ID peab olema hex UUID.

Append-only latest-valik kasutab `(created_at, revision)`. Viiele põhitabelile lisatakse identity-revision ja latest-indeksid; nelja registri INSERT-id võtavad logical-ID järgi advisory transaction lock'i. Kasutaja revocation ja mitteaktiivsus säilivad ning stale INSERT ei taasta kustutatud registrikirjet ega vanemat API-võtit.

`DSL/Liquibase/init.sql` on tühja andmebaasi koondatud skeem ilma dev-seedita; master-changelog säilitab olemasolevate installide migratsiooniajaloo. Mõlemat skeemiteed kontrollivad samad 27 SQL-regressiooni. Acceptance'i tulemused ja juhised asuvad `acceptance_test_preparation.md` failis.

Confluence'i olemasolevat versiooniajalugu tuleb sünkroonimisel säilitada; see fail ei asenda seal varem talletatud ajalookirjeid.
