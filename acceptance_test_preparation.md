# Vastuvõtutestide ettevalmistus

Kuupäev: 14.09.2026. Aluseks on `dev`-i ühendatud [runtime-uuenduse PR #153](https://github.com/kemit-ee/efti-gate-ee/pull/153), merge-commit `f0ce9f0`. Selle runtime-koodi viimane commit on `2a3f984`; uuenduse algne alus oli `15fade8`.

Guard'i ja SQL-i järelparandused asuvad harus `codex/harden-guards-and-sql-latest-rows`, [draft-PR #155](https://github.com/kemit-ee/efti-gate-ee/pull/155). Need on eraldi PR-is, sest #153 on juba ühendatud. Allpool on ühendatud runtime-uuenduse ja uue paranduste komplekti muudatused eraldi välja toodud.

## Komponendid

| Komponent | Enne PR #153 | Kasutusele võetud versioon | Muudatuse koht |
|---|---|---|---|
| Põhi-Ruuter | `turnerrainer/ruuter:0.9.15-rc` | `turnerrainer/ruuter:0.10.0-rc` | `docker/ruuter/Dockerfile` |
| Avalik developer-mock | `turnerrainer/ruuter:0.9.15-rc` | `turnerrainer/ruuter:0.10.0-rc` | `docker/ruuter-xroad-mock/Dockerfile` |
| ReSQL | `turnerrainer/resql:0.2.0-alpha` | `turnerrainer/resql:0.4.2-alpha` | `docker/resql/Dockerfile` |
| TIM | `turnerrainer/tim:0.3.0-alpha` | `turnerrainer/tim:0.4.0-alpha` | `docker/tim/Dockerfile` |

JVM 25, PostgreSQL 18, Kotlin 2.4.20 ja UI sõltuvused olid runtime-PR-i aluses juba olemas; see paranduste komplekt neid versioone ei muuda. Tabel kirjeldab konkreetseid kasutusele võetud image-tage, mitte tulevaste versioonide saadavuse kontrolli.

## Runtime-ühilduvuse muudatused (PR #153)

- Ruuteri uus 30-sekundiline inbound-vaiketimeout tõsteti `ruuter.yaml`-is 90 sekundini. See mahutab olemasoleva 65–70-sekundilise G2G timeout-ahela.
- Ruuter dekodeerib upstream-vastuse `Content-Type` järgi. eDelivery ja multiplexer renderdavad String-vastuseid toore XML-ina, mitte JSON-stringina; ka tühi polling-vastus on selle lepinguga kooskõlas.
- ReSQL-i distroless-image kasutab UID-d 65532. Konfiguratsioon ja SQL kopeeritakse sellele UID-le loetavana; shell/curl-healthcheck asendati `/app/resql health --url http://127.0.0.1:8090/health` käsuga.
- `resql.yaml` deklareerib `security.trust_network: true`. ReSQL jääb sisemise võrgu usalduspiirile; arenduse host-port on localhostil.
- ReSQL-i non-2xx vastuse keha on nüüd `[]` koos `X-Resql-Error-Code` ja `X-Resql-Error-Message` päistega. DSL-id kontrollivad HTTP-staatust enne tühja tulemuse tõlgendamist puuduvaks kirjeks.
- Admini, autentimise, consignment-upload'i, dataset/follow-up ja G2G otsingu veateed täpsustati. Kohaliku otsingu DB-viga tagastab 502 ega käivita ekslikult cross-gate fan-out'i.
- Readiness tagastab DB-rikke korral 503. Lisati ka tuntud route'i vale HTTP-meetodi 405 regressioonikontroll.
- TIM introspection nõuab kliendiautentimist. `ruuter` kliendi secret tuleb keskkonnast `TIM_INTROSPECT_RUUTER_SECRET`; Compose'i arendusväärtus ei ole tootmise secret. Olemasolevad `/jwt/userinfo` kutsed säilitavad kasutatava compatibility-lepingu.
- Ruuteri uus 16 MiB upstream-vastuse piir jäi vaikeseadistusse. Suurima päris XML-vastuse sobivus tuleb vastuvõtukeskkonnas kontrollida.

## Guard'i ja autentimise järelparandused

- `dev-login` on jälgitavas konfiguratsioonis ja Docker build'i vaikeseadistuses keelatud (`DEV_LOGIN_ENABLED=false`) ning tagastab 404 enne TIM-i kutset. `compose.override.yml` lubab selle ainult local/dev ja CI-image'i build-argumendiga. Konstandi muutmine vajab image'i rebuild'i.
- Platformi sisetokeni aktsepteerimine nõuab nii seadistatud tokeni kui päise mitte-tühjust. Sisetokeniga upload nõuab lisaks `X-Platform-Id` päist.
- eDelivery võtab `X-Platform-Id` inbound-kutse platformi party-ID-st. Kuna handler saab vastuse `RequestKey`, on algne saatja selle `receiverId`, mitte meie gate'i `senderId`. HTTP header'i edastamine ja õige poole valik on JVM-testidega kaetud.
- Platformi API-võti lahendab ainult ONLINE/OFFLINE platformi; DISABLED ja DELETED ei autentita. Vastus peab olema päris massiiv ühe identiteediga. Sama massiivi kuju kontroll kehtib X-Road authority guard'is.
- Nii täieliku FTI004 kui lühikese upload'i mapper-tulemus peab kuuluma guard'i lahendatud platformile ja `OWN_GATE_ID` gate'ile. Võõras omanik annab 403 enne DB INSERT-i. Välise kutse `X-Gate-Id` ei määra kohaliku upload'i omanikku.
- `consignments-xml` wrapper edastab algse API-võtme/sisetokeni ja platformi päise. API-võtmega kutset ei muudeta sisetokeniga privilegeeritud kutseks. Lapse 400/403/500/502 vastus ei muutu wrapper'is 201 XML-vastuseks.
- Mapperi vigane XML/non-200 tulemus peatab upload'i. 400 antakse vigase sisendi korral, mapperi transport-/serveririke annab 502.
- X-Road ja standalone-mock kontrollivad hexadecimal UUID kuju `8-4-4-4-12` RegExp'iga. Mitte-hex sama pikkusega väärtus annab 400 `INVALID_REQUEST_ID`; uppercase hex on lubatud.
- TIM-i puuduliku identiteedimetadata korral autentimine keelatakse. Mitme aktiivse sama isikukoodi tulemuse korral ei valita esimest kasutajat. TIM 5xx on 503, mitte väide vigasest credential'ist.
- Põhi-Ruuteri arendusport bind'itakse `127.0.0.1`-le. `/xroad/**` peab vastuvõtu/tootmise ingressis olema ligipääsetav ainult Security Serverist; päise kuju kontroll ei autentida võrgu kaudu suvaliselt ligipääsetavat saatjat.
- Eemaldati aegunud kommentaar template-mootori mittetöötamise kohta ja surnud ADMIN-rolli veaharu. Süsteemi reegel jääb: iga provisioneeritud kasutaja on admin, kasutajal pole rollivälju.

## SQL-i järelparandused ja API mõju

- Kasutaja autentimine ja isikukoodi olemasolu/lookup leiavad esmalt isikukoodi järgi kandidaat-ID-d, seejärel iga ID viimase rea ning alles siis kontrollivad aktiivsust ja isikukoodi. Vana aktiivne rida ega parandatud isikukoodi ajalooline rida ei muutu tavapärase järjestuse korral taas kehtivaks.
- Kasutaja nime muutmine kannab edasi `secret_hash`, `token_revoked_at` ja `is_active`. Isikukoodi muutmine määrab uue tokenitühistamise cutoff'i. Tokeni broadcast-revocation kannab edasi ka break-glass parooli hash'i.
- Gate/platform ping ja API-võtme rotatsioon kontrollivad viimase rea staatust pärast latest-row valikut. Ping ei aktiveeri DISABLED/DELETED kirjet. Admini ping annab DELETED korral 404 ja DISABLED korral 409 enne välist ping-kutset; DB-rike kontrollitakse ka pingi kirjutus- ja verify-sammus.
- Gate/platform/authority PUT ei loo puuduvat logical-ID-d ega taasaktiveeri juba kustutatud latest-kirjet. Üks SQL-statement teeb viimase rea kontrolli ja INSERT-i; paralleelsete stale-kirjutuste serialiseerimine on allpool kirjeldatud eraldi migratsioonikavandis.
- Platformi API-võtme lookup kitsendab hash-indeksiga kandidaat-ID-d enne nende viimaste versioonide lahendamist. Latest-väliskiht kontrollib uuesti hash'i ja staatust, seega rotatsiooni eelne võti ei valideeru ajaloolise rea tõttu.
- Seitsme equipment-filtri EQ kasutab `array @> ARRAY[value]`. NE tähendab väärtuse puudumist, mitte „leidub mõni erinev element”. NE käsitleb NULL-massiivi tühjana; `[A,B] NE A` on false ja `[]/NULL NE A` on true.
- Transport-means existence/local päringud materialiseerivad kolme identifier-indeksi järgi ainult kandidaatide võtmed ja lahendavad igaühe viimase versiooni `LATERAL ... LIMIT 1` lookup'iga. See väldib kogu consignments-tabeli latest-sort'i ning säilitab corrected-ID/soft-delete semantika. Tegemist on sama tabeli lookup'iga, mitte cross-table JOIN'iga.
- Kõigi caller-controlled `LIMIT/OFFSET` päringute limiit on maksimaalselt 1000, negatiivne limit/offset clamp'itakse nulliks. Registry listil on väliskihis `ORDER BY id`; logidel aeg ja `row_id`.
- Latest-valik ja consignment-otsingu anti-join kasutavad sama `(created_at, row_id)` järjekorda. See eemaldab võrdse ajatempli kahe current-rea tagastamise, kuid juhuslik UUID ei tõenda nende kronoloogilist järjestust. Selle piirangu täielik lahendus vajab allpool kirjeldatud migratsiooni.
- Consignment-versiooni logical-key on `(platform_id, dataset_id)`. Upload-verify edastab ka platformi ja gate'i. Sama dataset UUID eri platformidel ei vali verify käigus teise platformi latest-rida.
- `DELETE /admin/v1/consignments/{datasetId}` nõuab nüüd query-parameetreid `platformId` ja `gateId`. Puudumise korral on vastus 400; kirje puudumise/vale omaniku korral 404. UI ja HTTP-testid saadavad need parameetrid. Muutus on vanade dataset-only DELETE klientide jaoks sisendlepingu muudatus.
- Sisemine `get_consignment_by_id` tagastab ilma platformi kitsenduseta ühe latest-rea iga platformi kohta. Status-route lubab valikulist UIL-i kitsendust. `get_consignment_xml` ja soft-delete SQL nõuavad täielikku omanikku; gate-filter ei saa valida vana gate'i ajaloolist rida.
- Admini consignment-list säilitab XML-i, sest olemasolev UI XML/andmete vaade seda kasutab. XML-i eemaldamine vajaks eraldi detail-API/UI muudatust.

## Kontrollitud tulemused

| Kontroll | Tulemus | Ulatus |
|---|---|---|
| Runtime-PR #153 full-stack HTTP | 207 päringut, 0 ebaõnnestunud testi | `2a3f984`, [CI run](https://github.com/kemit-ee/efti-gate-ee/actions/runs/34848316005) |
| Ruuter 0.10.0 `dsl-lint` | Põhi-DSL 74 faili: 0 viga, 20 hoiatust; mock 9 faili: 0 viga/hoiatust | Hoiatused on proosas olevad em-dash'id ja kolm varasemat unreachable setup-sammu; need ei ole puhta linti väide |
| Ruuteri regressioonistsenaariumid | 30/30 läbis | Guard, ownership, mapperi vead, identity response, dev-login, existing runtime checks |
| Standalone-mock UUID | 2/2 läbis | Invalid non-hex ja valid uppercase hex |
| SQL regressioonid | 23/23 läbis | Sisaldab kõigi 42 endpointi PREPARE'i tegelike deklareeritud bind-tüüpidega `app` rolli all |
| JVM unit testid | eDelivery, xml-mapper, multiplexer läbisid | Uued sender-ID ja HTTP header'i testid ning olemasolevad XML/wire-testid |
| UI production build | Läbis (`npm ci` + `npm run build`) | Vite 7.3.6; olemasolev glob `as` deprecation-hoiatus |
| Ehitatud tootmis-Ruuteri autentimistestid | 5/5 läbis | Dockerfile vaikeargumendiga image; dev-login keelamise test ei override'i konstanti |
| Testitud migratsioonikavand | 27/27 läbis ainult ajutises baasis | 23 regressiooni + 4 migratsiooni-/konkurentsikontrolli; migratsioon ei ole rakendatud |
| Input-contract / diff check | Läbis | `scripts/validate-dsl.py`, `git diff --check` |

Paranduste PR-i [full-stack CI](https://github.com/kemit-ee/efti-gate-ee/actions/runs/34858408531) on käivitatud; tulemus on veel ootel. Ühendatud #153 tulemus ei asenda selle PR-i kontrolli.

### Jõudluskatse

PostgreSQL 18, 100 000 sünteetilist consignment-rida, üks harvaesinev equipment-ID match. Vana ja uus päring mõõdeti samal baasil pärast `VACUUM (ANALYZE)`, ilma `enable_seqscan` sundkeelamiseta. Mõlemad `plan_cache_mode` variandid valmistavad päringud ette; need ei ole ainult inline-literal EXPLAIN-id.

| Päring | Plaan | Enne (ms) | Pärast (ms) | Shared-hit bufferid enne/pärast |
|---|---|---:|---:|---:|
| Existence, custom | Seq Scan/Semi Join → BitmapOr (B-tree/GIN) + latest-index | 163.658 | 1.163 | 3669 / 28 |
| Existence, generic | Seq Scan/Semi Join → BitmapOr (B-tree/GIN) + latest-index | 43.877 | 1.361 | 3686 / 28 |
| Local lookup, custom | Seq Scan/latest-sort → BitmapOr + latest-index | 59.867 | 3.666 | 3690 / 32 |
| Local lookup, generic | Seq Scan/latest-sort → BitmapOr + latest-index | 39.542 | 6.185 | 3673 / 32 |

Värske bulk-INSERT-i järel, enne GIN pending-list'i hooldust, valis sama kandidaatfilter Seq Scan'i ja uued päringud olid umbes 29–37 ms. Seetõttu tuleb vastuvõtukeskkonnas kontrollida autovacuum'i/GIN pending-list'i seisu ja päris andmejaotust. Üksikkatsed arendusmasinal ei tõenda tootmise SLA-d, sagedase identifikaatori ega kogu 42 päringu optimaalset tootmisplaani. Puuduvad tootmise `pg_stat_statements`, mahud ja koormusprofiil.

## Vastuvõtukeskkonna ettevalmistus

1. Kasuta PR-is kirjeldatud image'e ja kontrolli tegelikud running tag'id/digest'id. Kui muutub `constants.ini`, tuleb Ruuter rebuild'ida või kasutada keskkonna kontrollitud constants-mount'i; DSL watch üksi sellest ei piisa.
2. Provisioneeri päris `TIM_INTROSPECT_RUUTER_SECRET`, TIM admin-token ja mitte-tühi siseteenuse token. Ruuteri/eDelivery token peab kattuma. Dev näidisväärtusi ei loeta keskkonna provisioning'uks.
3. Tootmise/vastuvõtu Ruuteri build peab hoidma `DEV_LOGIN_ENABLED=false`. Local/CI override lubamine on teadlik erand; ära kasuta seda tootmise build'i vaikeseadistusena.
4. Seed'i testplatform ja authority koos teadaolevate subset-õigustega; kasuta eri dataset UUID-sid või täpset UIL-i. Sisetokeniga upload'i jaoks peab eDelivery edastama platformi party-ID.
5. Kontrolli ingressist, et `/xroad/**`, `/efti/internal/**` ja gate-internal `/efti/api/v1/**` ei ole suvalisele välisele kliendile kättesaadavad. Testi X-Roadi Security Serveri kaudu.
6. Dataset-only consignment DELETE klient tuleb uuendada saatma `platformId` ja `gateId`. Paginaatorid peavad arvestama maksimaalse 1000-rease lehega.

## Vastuvõtustsenaariumid

| ID | Tegevus | Oodatav tulemus |
|---|---|---|
| AT-01 | Tootmiskonfiguratsiooniga POST `/auth/dev-login`, aktiivse admini isikukood | 404, JWT-d ei väljastata |
| AT-02 | TARA login, admin GET; logout ja sama token uuesti | Login/admin õnnestub; token pärast logout'i keelatud |
| AT-03 | Kasutaja tombstone või isikukoodi vahetus, vana token/identiteet | 401; nime muutmine ei tühista revocation-markerit ega reaktiveeri kasutajat |
| AT-04 | Platform API-võtme rotatsioon | Uus võti töötab, vana võti ei tööta; DISABLED/DELETED ei autentida |
| AT-05 | Tühi token konfiguratsioonis + tühi sisetokeni päis | 401, INSERT-i ei toimu |
| AT-06 | Oma platformi võtmega foreign platform/gate UIL (REST ja XML-wrapper) | 403, foreign kirjet ei lisata |
| AT-07 | Sisemine eDelivery upload koos algse platformi party-ID-ga | 201 XML; puuduva/võõra platformi identiteedi korral 401/403 |
| AT-08 | Vigane XML ja katkine mapper/ReSQL ühendus | 400 või semantiline 502/500; neid ei raporteerita 201 õnnestumisena |
| AT-09 | X-Road-Id `gggggggg-gggg-gggg-gggg-gggggggggggg`; uppercase valid UUID | Esimene 400 `INVALID_REQUEST_ID`, teine lubatud |
| AT-10 | Sama dataset UUID kahel platformil; verify ja ühe UIL-i DELETE | Mõlemad õigesti eristatavad; kustutatakse ainult valitud omanik |
| AT-11 | Identifier corrected/replaced või consignment DELETED/INACTIVE | Vana identifier ei anna existence/local ACTIVE match'i |
| AT-12 | Equipment `[A,B]`: EQ A, NE A, NE C; NULL/empty massiiv | Vastavalt match, no match, match; puuduv massiiv sobib NE-ga |
| AT-13 | DELETED/DISABLED gate/platform ping ja kustutatud registri PUT | Ei taasaktiveerita, välist pingi ei tehta keelatud olekus |
| AT-14 | Local ja G2G dataset/search/follow-up XML + polling | Toores XML, õige Content-Type; ootamine mahub 90 s inbound-aega |
| AT-15 | ReSQL non-2xx, readiness DB-rikke ajal, TIM introspection ilma/koos client auth'iga | ReSQL error-päised ja `[]`; readiness 503; introspection client auth enforced |
| AT-16 | Suurim päris dataset XML ja päris koormuse custom/generic plaanid | 16 MiB piiresse sobiv või teadlik konfiguratsioonimuutus; jõudlusmõõtmine päris mahul |

## Korratavad arenduskontrollid

```sh
python3 scripts/validate-dsl.py
python3 tests/sql/regression.py
python3 tests/sql/regression.py --performance
docker run --rm -v "$PWD:/workdir" -w /workdir turnerrainer/ruuter:0.10.0-rc dsl-lint --dsl DSL/Ruuter --constants constants.ini
docker run --rm -v "$PWD:/workdir" -w /workdir turnerrainer/ruuter:0.10.0-rc dsl-test --dsl DSL/Ruuter --tests DSL-tests --constants constants.ini
docker run --rm -v "$PWD:/workdir" -w /workdir turnerrainer/ruuter:0.10.0-rc dsl-test --dsl DSL/Ruuter-xroad-mock --tests DSL-mock-tests --constants constants-xroad-mock.ini
cd code && ./gradlew edelivery:test xml-mapper:test multiplexer:test
```

SQL-käivitaja loob oma nimega tmpfs-PostgreSQL konteineri, avaldab null host-porti ja eemaldab konteineri ka vea korral. Olemasolevaid DB-sid see ei kasuta. Mõlemad DSL CI-tööd käivitavad SQL-regressioonid ja mocki UUID-testid.

## Eraldi heakskiitu vajav migratsioonikavand

Migratsioonikavandi faili lisamine PR-i peatati automaatse õiguskontrolliga. Seda ei ole `DSL/Liquibase/changelog` all ja standardne CI/Compose seda ei rakenda. Eraldatud testibaasis kontrollitud kavand:

- Lisab `revision BIGINT GENERATED ALWAYS AS IDENTITY` tabelitele `users`, `gates`, `platforms`, `authorities`, `consignments`.
- Uuendab nende viit latest-indeksit kujule `(logical key, created_at DESC, revision DESC)` ja annab `app` rollile vastavate sequence'ide kasutusõiguse.
- Lisab neljale registritabelile BEFORE INSERT triggeri, mis võtab logical-ID järgi advisory transaction lock'i, määrab järjestusnumbri luku all ja kontrollib seejärel värsket latest-kirjet.
- Takistab kustutatud gate/platform/authority taasaktiveerimist paralleelse stale-write'iga; kasutajal säilitab mitteaktiivsuse ja suurima token-revocation cutoff'i; platformil ei kirjuta vanema generation-ajaga API-võti uuemat võtit üle.
- Vajab SQL latest-võrdluste ühist üleminekut `row_id` tie-breaker'ilt `revision`-ile ja sama reeglit välises arhiveerimises.

Kavandi neli lisatesti tõendasid equal-timestamp järjestust, paralleelse kustutamise järel stale-update'i keelamist, kasutaja revocation/aktiivsuse säilimist ning platformi vana võtme taastamise keelamist. 23 olemasoleva regressiooni ja nende nelja kontrolli tulemus oli 27/27.

Püsiva migratsiooni ADD COLUMN/index-build võtab tabelilukke ja võib olemasolevat tabelit ümber kirjutada; seda tuleb mahu järgi planeerida. Vanade võrdsete ajatemplitega kirjete tegelikku ajaloolist järjekorda ei saa tagantjärele taastada: backfill saab neile üheselt määratud järjestuse. Migratsioonifaili lisamise heakskiit ei ole tootmises käivitamise ega PR-i merge'i heakskiit.

Kuni migratsiooni pole heaks kiidetud/rakendatud, ei loeta UUID järgi tie-breaker'it kronoloogiliseks lahenduseks ega SQL latest-first üksi paralleelsete registrikirjutuste serialiseerimise tõendiks. Paranduste PR jääb selle küsimuse jaoks draft'iks.
