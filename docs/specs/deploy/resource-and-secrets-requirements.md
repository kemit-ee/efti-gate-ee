# Ressursside ja saladuste (secrets) nõuded — paigalduse alusdokument

**Sihtgrupp:** infra/admin, kes teeb eFTI värava esmapaigalduse (K8s / Helm, `services/efti/devops`
repo Argo CD kaudu — vt [`.gitlab-ci.yml`](../../../.gitlab-ci.yml)). Dokument põhineb reaalsel
jooksval süsteemil ([`compose.yml`](../../../compose.yml), [`constants.ini`](../../../constants.ini),
`docker/*`), mitte spekulatiivsel arhitektuuril. Vt ka [`../non-functional.md`](../non-functional.md)
§3 (topoloogia) ja [`README.md`](README.md) (mis siin kataloogis muidu on).

Läbivalt: `dev` väärtus tähendab, et see on `compose.yml`/`constants.ini`-sse **kõvasti kirjutatud
arendusväärtus** — see EI TOHI kunagi jõuda tootmiskeskkonda muutmata kujul.

---

## 1. Komponendid ja ressursivajadus

Baas on `compose.yml`-i CI/dev-piirangud (koormustestitud [`docs/performance/`](../../performance/)
all — vt ADR-009/010). Need on **alampiir katsetatud kraami jaoks**, mitte tootmise dimensioneering;
tootmises soovita alustada "Soovituslik prod" veerust ja mõõta reaalse koormusega.

| Teenus | Vajalik prod? | CPU (compose) | Mälu (compose) | Soovituslik prod (start) | Olek |
|---|---|---|---|---|---|
| `database` (PostgreSQL 18, `efti` DB) | JAH | 2.0 | 1 GiB | 2 vCPU / 2 GiB + eraldi ketas, **eraldi mõõdetud** ADR-009 jõudlustööga | Stateful — vajab PV/PVC-d ja backupit |
| `archive-database` (PostgreSQL 18, `efti_archive` DB — saadetiste külmladu, vt issue #143/#56) | JAH | 0.5 | 512 MiB | 0.5 vCPU / 512 MiB + eraldi ketas, kasv sõltub `keepDays` retention-seadest (`purge_archived_consignments.sql`, vaikimisi 2555 päeva) | Stateful, **eraldi instants** kui `database` — vajab oma PV/PVC-d ja backupit; ligipääsetav ainult ReSql `archive` andmeallika kaudu, mitte otse |
| `resql` (ReSql 0.4.3-alpha) | JAH | — (määramata!) | — (määramata!) | 0.5 vCPU / 512 MiB | max_connections 75 (resql.yaml) — jäta PG max_connections=100 juurde varu teistele klientidele. Kaks andmeallikat: `efti` ja `archive` |
| `ruuter` (Ruuter 0.12.1-rc, `/efti` + `/xroad` + `/ops`) | JAH | 2.0 | 512 MiB | 1 vCPU / 512 MiB (koormustestitud, vt performance-dok) | — |
| `ruuter-xroad-mock` (avalik X-Roadi arendaja-mokk, `/developer/**`) | VALIKULINE | 0.25 | 128 MiB | 0.25 vCPU / 128 MiB | Standalone, andmebaasita — vt `docs/developer/x_road_developer_mock.md`. Jäta välja, kui väliseid platvormiarendajaid ei teenindata. |
| `tim-init` (ühekordne ettevalmistus) | JAH, ühekordne | — | — | 0.1 vCPU / 64 MiB | Init-container enne `tim`-i: CA-bundle ja (dev-is) JWT võtme genereerimine — vt §3.3 |
| `registry` (nginx, staatiline register) | JAH | — | — | 0.1 vCPU / 64 MiB | Image sisaldab registrit (ADR-011), avaldatud pordita — vt §3.4 |
| `cronmanager` (turnerrainer/CronManager 0.2.3-alpha, `/ops/v1/*` ajastaja) | JAH | 0.25 | 64 MiB | 0.1 vCPU / 64 MiB | Jobid image'i sees (`DSL/CronManager/efti-ops.yaml`), avaldatud pordita, oma andmebaasita — vt §3.2 ja `docs/specs/deploy/README.md` (CronManager). Chart: devops `charts/cronmanager` ([!3](https://gitlab.kemitaws.ee/services/efti/devops/-/merge_requests/3)), 1 replika (teine käivitaks iga jobi kaks korda) |
| `tim` (Token & Identity Manager 0.4.1-alpha, distroless) | JAH | — | — | 0.5 vCPU / 256 MiB | Oma PostgreSQL DB (vt §3) |
| `tim-database` (PostgreSQL 18, `tim` DB) | JAH | — | — | 0.5 vCPU / 512 MiB | Stateful, eraldi instants(dev)/eraldi skeem-või-instants (prod, vali) |
| `ui` (nginx + Vite/Svelte build) | JAH | — | — | 0.25 vCPU / 128 MiB | Staatiline sisu, väga kerge |
| `edelivery` (Kotlin/klite, AS4 G2G) | JAH | — | 512 MiB | 0.5 vCPU / 512 MiB | — |
| `xml-mapper` (Kotlin/klite, XML↔JSON) | JAH | — | 512 MiB | 0.5 vCPU / 512 MiB | — |
| `liquibase` (migratsioonijooksja) | JAH, ühekordne | — | — | 0.5 vCPU / 256 MiB | Job/init-container, mitte pikaajaline teenus |
| `tara-mock` | EI (ainult dev/test) | — | — | — | Tootmises kasutatakse päris TARA teenust (vt §4) |

**Tähelepanek:** `resql`, `tim`, `tim-database`, `ui`, `xml-mapper`-il
puuduvad `compose.yml`-is CPU piirid täielikult (ainult osadel on `mem_limit`). Enne prod-i
dimensioneerimist tuleks kas (a) käivitada `docker stats` all koormustest ja panna paika reaalsed
piirid, või (b) alustada "Soovituslik prod" veeru väärtustest ja mõõta Kubernetese
requests/limits-ga elus liikluse peal.

---

## 2. Läbivad (mitte-saladus) konfiguratsiooni väärtused

Need on `constants.ini`/`compose.yml`-i osa, aga pole saladused — vajavad ainult keskkonnapõhist
väärtust:

| Muutuja | Kirjeldus | Dev väärtus |
|---|---|---|
| `OWN_GATE_ID` | Selle värava eFTI identifikaator. Peab olema sama `constants.ini`-s (Ruuter) ja `edelivery`/`xml-mapper` env-is, ning võrduma `registry/gates/<OWN_GATE_ID>.yml` failinime ja AS4 võtmehoidla aliasega (§3.5) | `EU-EE` |
| `RUUTER_URL`, `RESQL_URL`, `REGISTRY_URL`, `EDELIVERY_URL`, `XML_MAPPER_URL`, `TIM_URL` | Teenustevahelised URL-id (K8s-is Service DNS-nimed) | `http://<service>:<port>` |
| `REGISTRY_REFRESH_SECONDS` | Kui tihti `edelivery` registrit uuesti loeb | `10` |
| `TURVIS_INTEGRATION_MODE` (`edelivery`) | `legacy` (vaikimisi: ainult protsessisisene krüpto, Turvist ei kutsuta), `shadow` või `turvis` (Turvis autoriteetne). Vigane väärtus peatab käivituse | `legacy` |
| `TURVIS_URL` (`edelivery`) | Turvise baas-URL; nõutav, kui režiim pole `legacy`. **Käivituse guard:** kui host on `turvis-mock` (ka `turvis-mock.` ja `turvis-mock.<järelliide>`, mis tahes tähesuurus) ja mõni muutujatest `ENVIRONMENT`/`APP_ENV`/`DEPLOY_ENV`/`ENV` (komaeraldatud) on `prod`/`production`/`staging`, `edelivery` ei käivitu (igas režiimis; IP-aadressi hostinimekontroll ei näe) | `http://turvis-mock:8091` (ainult `compose.override.yml`) |
| `TURVIS_INTERNAL_TOKEN` (`edelivery`, **saladus**) | Turvisele pühendatud mandaat päises `X-Internal-Service-Token` (SPEC §11.2); nõutav, kui režiim pole `legacy`; **ei tohi olla võrdne `INTERNAL_SERVICE_TOKEN`-iga** (käivitus keeldub). Tootmises Secret, mitte `compose.yml` | `dev-turvis-mock-token` (ainult `compose.override.yml`, mocki enda dev-token) |
| `DEV_LOGIN_ENABLED` (`constants.ini`) | TARA-vaba arendus-sisselogimine | `true` — **prod-is peab olema `false`** |
| TIM `public_base_url`, `allowed_redirect_uris` (`docker/tim/tim.yaml`) | TIM-i avalik aadress ja lubatud tagasisuunamise URL-id | `http://localhost:8000/...` — **keskkonnapõhine, prod-domeen tuleb lisada** |
| `LOGGER_CLASS` | Kotlin-teenuste logimistase/formaat | määramata (vaikimisi) |
| `VITE_USE_PROD_TARA_URL` | UI build-arg, kas UI viitab reaalsele TARA-le | `true` (image-build juba seab) |

---

## 3. Saladused (secrets) — mis, kus kasutusel, kuidas paigaldada

Iga rida: **nimi** → **kes tarbib** (konteiner/env muutuja) → **otstarve** → **dev väärtus (ÄRA
kasuta prod-is)** → **paigalduse märkus**.

### 3.0 Saladuste haldamise põhimõtted

**Üks allikas, üks tarneviis.** Iga saladus elab keskkonna saladuste hoidlas (AWS SSM Parameter
Store, prefiks `/efti/<env>/…`; alternatiivina Vault) ja jõuab konteinerisse **ainult**
`ExternalSecret` → Kubernetes Secret kaudu (§7). Saladust ei panda Helm `values.yaml`-i, Git-i,
CI-muutujasse ega image'isse. Ainus teadlik erand on platvormide API võtmed registris (§3.4, ADR-011 §5).

**Kolm tarneviisi** — vali iga saladuse jaoks see, mida tarbija oskab lugeda:

| Tarneviis | Saladused | Mida muutmine nõuab |
|---|---|---|
| **Env muutuja** (`secretKeyRef`) | `POSTGRES_PASSWORD`, `RESQL_*_PASSWORD`, `LIQUIBASE_COMMAND_PASSWORD`, `AUDIT_SALT`, `TIM_DATABASE_URL`, `TIM_ADMIN_TOKEN`, `TIM_INTROSPECT_RUUTER_SECRET`, `TIM_TARA_CLIENT_SECRET`, `KEYSTORE_PASSWORD`, `INTERNAL_SERVICE_TOKEN` (Kotlin-teenused) | Podi taaskäivitus |
| **Fail Secretist** (`volumeMount`) | Ruuteri `constants.ini` (§3.2), TIM JWT võti (§3.3), AS4 `own.p12` (§3.5) | Podi taaskäivitus (Ruuter loeb `constants.ini` ainult käivitusel) |
| **Image'i sees** | Platvormide `apiKey` (`registry/`, §3.4) | `registry` image'i uus build + paigaldus |

**Paarid, mis peavad kokku sobima.** Mitu saladust on kahes kohas korraga — muuda alati mõlemad
samas paigalduses, muidu katkeb ühendus:

| Väärtus | Kohad |
|---|---|
| `efti` DB parool | `database` `POSTGRES_PASSWORD` = `RESQL_EFTI_PASSWORD` = Liquibase parool |
| `efti_archive` DB parool | `archive-database` `POSTGRES_PASSWORD` = `RESQL_ARCHIVE_PASSWORD` |
| `TIM_ADMIN_TOKEN` | `tim` env = Ruuteri `constants.ini` |
| `INTERNAL_SERVICE_TOKEN` | Ruuteri `constants.ini` = `edelivery` env = `xml-mapper` env |
| `ARCHIVE_OPS_TOKEN` | Ruuteri `constants.ini` = CronManageri `OPS_AUTHORIZATION` (kujul `Bearer <ARCHIVE_OPS_TOKEN>`) |
| AS4 sert | `own.p12` (§3.5) = `registry/gates/<OWN_GATE_ID>.yml` `eDeliveryCert` = partnerite registrid |

**Rotatsioon.** Üldreegel: genereeri uus väärtus → kirjuta SSM-i → lase ExternalSecretil Secret
uuendada (või `kubectl annotate externalsecret … force-sync=$(date +%s)`) → taaskäivita kõik
tarbijad (`kubectl rollout restart`). Erijuhud:

- **Andmebaasi parool**: muuda esmalt PostgreSQL-is (`ALTER ROLE … PASSWORD`), siis SSM-is, siis
  taaskäivita `resql` (ja järgmine Liquibase'i jooks kasutab uut). Vahepeal ReSql uusi ühendusi ei loo.
- **`AUDIT_SALT`**: **ära vaheta** olemasolevas keskkonnas — `audit_log`-i isikukoodide räsid ei ole
  siis enam vanadega võrreldavad. Vahetus = teadlik otsus koos auditiandmete käsitlusega.
- **TIM JWT võti**: vahetus logib kõik kasutajad välja (vanad JWT-d ei valideeru).
- **AS4 võti**: koordineeritud vahetus partneritega — §3.5 "Rotatsioon".
- **Platvormi API võti**: registrifaili muudatus + `registry` image'i paigaldus; uus võti antakse
  platvormile turvalist kanalit pidi (§3.4).
- **Lekke korral**: vaheta kohe, kontrolli `audit_log`-i ja Ruuteri/edelivery logisid lekkeaja kohta;
  `INTERNAL_SERVICE_TOKEN`-i ja `ARCHIVE_OPS_TOKEN`-i leke tähendab ka võrgupiiride (§6) ülevaatust.

**Ligipääs.** SSM-i `/efti/<env>/` loe- ja kirjutusõigus ainult infra/admin rollil;
`ExternalSecret`-i teenusekonto saab ainult lugeda. Kubernetes Secretite lugemisõigus ainult
nimeruumi administraatoril. Kuna `registry` image sisaldab API võtmeid, kehtib sama nõue
image-registri (Harbor) `registry/images` repositooriumi kohta.

### 3.1 Peamine andmebaas (PostgreSQL, DB `efti`)

| Saladus | Tarbija | Otstarve | Dev väärtus | Märkus |
|---|---|---|---|---|
| `POSTGRES_PASSWORD` | `database` teenus (`POSTGRES_PASSWORD` env) | `efti` PG kasutaja parool | `01234` | Peab kattuma järgnevate kahega — kolm eri kohta, **üks väärtus** |
| `RESQL_EFTI_PASSWORD` | `resql` teenus | ReSql `efti` andmeallika parool (`resql.yaml` → `password_env: RESQL_EFTI_PASSWORD`, krüptovaba URL, vt `resql.yaml` kommentaari) | `01234` | Peab kattuma `POSTGRES_PASSWORD`-iga |
| Liquibase `password` | `liquibase` konteiner (`DSL/Liquibase/liquibase.properties`) | Migratsioonide käitamise PG parool | `01234` (kõvasti failis!) | **Prod: ÄRA jäta faili sisse.** Liquibase 4.x loeb env muutujaid `LIQUIBASE_COMMAND_URL` / `LIQUIBASE_COMMAND_USERNAME` / `LIQUIBASE_COMMAND_PASSWORD`, mis kirjutavad properties-faili väärtused üle (sama muster mis ljvis2-devops kasutab, vt `environments/dev/values/ljvis2-config.yaml` selles repos) |
| `AUDIT_SALT` | `liquibase` (changelog property, `-DAUDIT_SALT=...`) | Isikukoodide räsimise sool `audit_log`-is | `dev-local-audit-salt-change-me-32chars` | **KRIITILINE**: kui puudub, jääb changelogisse lahendamata `${AUDIT_SALT}` literaalina ja isikukoodid räsitakse selle stringiga — kontrolli prod-is `current_setting('app.audit_salt')` ei ole `${AUDIT_SALT}` |
| `RESQL_ARCHIVE_PASSWORD` | `resql` teenus | ReSql `archive` andmeallika parool (`resql.yaml` → `project_datasource_map`, sama muster mis `RESQL_EFTI_PASSWORD`) | `01234` | Peab kattuma `archive-database` teenuse `POSTGRES_PASSWORD`-iga (§1). **Erinev väärtus kui `RESQL_EFTI_PASSWORD`-il** — kaks eri andmebaasi, kaks eri parooli, ainult dev-is kokkulangevad `01234` mugavuse pärast |
| `archive-database` `POSTGRES_PASSWORD` | `archive-database` teenus | `efti_archive` PG kasutaja parool | `01234` | Peab kattuma `RESQL_ARCHIVE_PASSWORD`-iga — kaks kohta, üks väärtus (samamoodi nagu `database`/`RESQL_EFTI_PASSWORD` paar) |

### 3.2 Gate-sisene teenustevaheline autentimine

| Saladus | Tarbija | Otstarve | Dev väärtus | Märkus |
|---|---|---|---|---|
| `INTERNAL_SERVICE_TOKEN` | Ruuter (`constants.ini`, `[#INTERNAL_SERVICE_TOKEN]`); saadetakse `X-Internal-Service-Token` päisena | Jagatud saladus gate-sisesteks kõnedeks (X-Roadi adapter → `efti/api/v1/authority/**`, edaspidi ka G2G sissetulev). **Selle valdaja saab ADMIN-taseme ligipääsu kõigile authority-marsruutidele.** | `dev-internal-service-token-change-me` | **`constants.ini` ei ole image'is** — compose paigaldab selle käivitusel (`./constants.ini:/app/constants.ini:ro`). `${ENV}`-asendust Ruuteris ei ole, seega tootmises on **kogu `constants.ini` üks Secret-fail** (vt allpool), mitte üksikud env muutujad. Ainus kaitse peale tokeni on,, et `/xroad/**` ja `/efti/api/v1/authority/**` pole avalikult ligipääsetavad — vt §5 |
| `ARCHIVE_OPS_TOKEN` | Ruuter (`constants.ini`, `[#ARCHIVE_OPS_TOKEN]`), `DSL/Ruuter/ops/.guard.yml`; välise CronManager'i saadetav `Authorization: Bearer`-päring | Ainus autentimine kõigi `POST /ops/v1/*` marsruutide peale (`archive-consignments`, `purge-archive`, `purge-async-responses`, `purge-search-results`, `refresh-read-models`) — literal compare, mitte JWT. **Selle valdaja saab käivitada saadetiste külmladustamise/purge'imise mistahes ajal.** Vt `docs/architecture/infrastructure/append_only_archival.md` | `dev-archive-ops-token-change-me` | Elab samas Secret-failis `constants.ini` (vt allpool); muutmine = Secreti uuendus + ruuteri taaskäivitus. CronManageri pool on sama väärtus env-muutujas `OPS_AUTHORIZATION` **koos `Bearer `-eesliitega** (CronManageri `headerEnvs` paneb kogu muutuja väärtuse päiseks); vahetamisel uuenda mõlemad ja taaskäivita ka `cronmanager` — kui muutuja puudub või on tühi, ei laadi CronManager ühtki jobi. `DSL/Ruuter/ops/` **ei ole nginx'i kaudu proksitud** (vt §6) — ainus kaitse on, et CronManager pöördub otse konteinerivõrgus, mitte ingressi kaudu |

| `CRONMANAGER_ADMIN_TOKEN` | `cronmanager` env | CronManageri enda admin-värav: `POST /execute|/stop|/reload` (jobi käsitsi käivitamine) ja `GET /jobs|/running` (`expose_*_publicly: false`). Ilma selleta CronManager 0.0.0.0 peal ei käivitu | `dev-cronmanager-admin-token-change-me` (`.env`) | `APP_ENV=production|staging|test` korral nõuab CronManager ≥ 32 märki ja keelab nõrgad mustrid (`dev-`, `change-me` jms). Port pole avaldatud — käsitsi käivitamiseks `kubectl port-forward`. SSM: `/efti/<env>/cronmanager/admin-token`; `OPS_AUTHORIZATION` koostatakse `cronmanager-secret`-is `/efti/<env>/ops/archive-token`-ist (peab võrduma `constants.ini` `ARCHIVE_OPS_TOKEN`-iga) |

**Ruuteri `constants.ini` tootmises.** Fail sisaldab nii mitte-saladusi (URL-id, `OWN_GATE_ID`,
`DEV_LOGIN_ENABLED`) kui ka kolme saladust (`TIM_ADMIN_TOKEN`, `INTERNAL_SERVICE_TOKEN`,
`ARCHIVE_OPS_TOKEN`), seega hoia **tervet faili** Secretina. Repo `constants.ini` on dev-fail ja on
mallina kasutatav; tootmises:

1. SSM-is `/efti/<env>/ruuter/constants-ini` (kogu faili sisu) **või** kolm eraldi parameetrit, millest
   `ExternalSecret` `target.template` koostab faili (eelistatud — tokenid saab eraldi vahetada).
2. Secret `efti-ruuter-constants`, võti `constants.ini`.
3. Ruuteri Deployment: `volumeMount` `mountPath: /app/constants.ini`, `subPath: constants.ini`,
   `readOnly: true`.
4. Kontrolli, et `DEV_LOGIN_ENABLED=false` ja URL-id viitavad klastri Service-nimedele.

`subPath`-mount ei uuene jooksvas podis — pärast Secreti muutmist tee alati `kubectl rollout restart deploy/ruuter`.

### 3.3 TIM (Token & Identity Manager) — **oma eraldi PostgreSQL andmebaas**

| Saladus | Tarbija | Otstarve | Dev väärtus | Märkus |
|---|---|---|---|---|
| `TIM_DATABASE_URL` | `tim` teenus | TIM'i oma PG andmebaasi ühendusstring (`postgres://user:pass@host/db`) | `postgres://tim:tim-secret@tim-database:5432/tim` | **See on TEINE andmebaas kui `efti`** — eraldi instants dev-is (`tim-database` teenus), prod-is otsusta kas eraldi instants või sama klastri teine DB |
| `TIM_ADMIN_TOKEN` | `tim` (`security.admin_token_env`, `tim.yaml`) JA Ruuter (`constants.ini` → `[#TIM_ADMIN_TOKEN]`, saadetakse `X-TIM-Admin-Token` päisena `auth/POST/{callback,logout,dev-login}.yml`-ist) | TIM'i admin-API autentimine | `dev-tim-admin-token` | **Peab kattuma mõlemas kohas** — sama väärtus kahes eri failis/teenuses |
| `TIM_TARA_CLIENT_ID` / `TIM_TARA_CLIENT_SECRET` | `tim` (`oauth2.providers.tara.client_id_env`/`client_secret_env`) | OIDC klient TARA vastu | `efti` / `efti-secret` (dev-is TARA-Mock vastu) | **Prod: reaalne TARA registreering** (RIA), mitte mock. Vt ka `allowed_redirect_uris` `tim.yaml`-is — prod domeen tuleb sinna lisada |
| TIM JWT allkirjastamisvõti | `tim` (`jwt.private_key_path: /opt/tim/keys/jwt-private.pem`, RSA, PKCS8 PEM, `key_id: efti-rs-1`) | Kasutajate sessiooni-JWT allkirjastamine | **Dev-is genereerib selle `tim-init`** (`docker/tim-init/init.sh`, `openssl genpkey`), kui faili köites pole | **KRIITILINE PROD-NÕUE**: ilma püsiva Secretita tekib igal uuel köitel uus võti ja kõik kasutajad logitakse välja. Genereeri üks kord: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem`; SSM-i `/efti/<env>/tim/jwt-private-key` (base64), ExternalSecret → Secret, mount `/opt/tim/keys/jwt-private.pem` (`readOnly`). TIM jookseb UID **65532**-na (distroless): Secreti volume'il `defaultMode: 0400` + pod `securityContext.fsGroup: 65532`, või `0444`. Kui fail on olemas, `tim-init` seda üle ei kirjuta |
| `TIM_INTROSPECT_RUUTER_SECRET` | `tim` (`introspection.clients[ruuter].client_secret_env`, `tim.yaml`) | Introspektsiooni-kliendi `ruuter` saladus (`required_client_auth: true`) | `dev-introspect-secret` | TIM nõuab väärtust käivitusel. Ruuteri DSL ei kutsu praegu introspektsiooni (`/jwt/userinfo` kasutab ühilduvusrežiimi), seega teist kohta, kuhu sama väärtus panna, hetkel ei ole — aga ära jäta dev-väärtust |
| TARA CA (`SSL_CERT_FILE`) | `tim` (`/tim-ca-bundle/ca-bundle.pem`) | TARA-Mocki isesigneeritud CA usaldamine | `tim-init` kopeerib TARA-Mocki CA | **Prod (päris TARA):** sea `tim-init`-ile `SKIP_TARA_MOCK_CA=1` või jäta `tara-mock-vault` köide ära; ära sea `SSL_CERT_FILE`-i mock-bundle'ile — päris TARA sert usaldatakse vaike-CA-dega |

### 3.4 `registry/` — **konfiguratsioon, mis on ühtaegu saladus ja image'i sees**

**Muutunud ADR-011-ga (§3 tarneviis, §4 andmebaas, §5 avatekstiline võti).** Väravate, platvormide ja asutuste register ei
ole enam andmebaasis (registritabelid kustutati) ega tule Admin-liidese kaudu. Allikas on repos olevad
YAML-failid `registry/{gates,platforms,authorities}/<id>.yml`; `scripts/registry-to-json.py`
valideerib ja konverteerib need **image'i build-ajal** JSON-iks ning `registry` image'i staatiline
nginx (port 8080, avaldatud pordita) serveerib neid ruuterile ja edelivery'le.

- **Runtime-mount'i ei ole.** `registry/` ei ole enam Secret ega ConfigMap, mida konteinerisse
  mount'ida — see on image'isse `COPY`-tud sisu (`docker/registry/Dockerfile`). Konteiner ei loe
  käivitusel midagi; kogu valideerimine käib build-ajal ja vigane fail **kukutab image'i buildi läbi**
  (CI jooksutab sama konverterit ka ilma Docker-buildita).
- **Iga keskkond buildib oma image'e.** Mount'i ei ole, seega `registry/**` tuleb enne `docker build`-i
  asendada keskkonna omaga (haru või CI samm, mis kirjutab kataloogi üle). **Sellest repos
  CI-builditud image serveerib dev-fixture'id** (`EU-EE`, `EU-MOCK`, platvorm `mock` avatekstilise
  võtmega `mock-secret-key`, `auth-*` testasutused) — tootmiskeskkond ei tohi seda muutmata kujul
  kasutada.
- `registry/gates/<id>.yml` `eDeliveryCert` / `tlsCert` ja `registry/platforms/<id>.yml`
  sertifikaadid on endiselt **operatiivsed andmed**, aga nüüd failina, mitte DB-veeruna.
- **Platvormi `X-Api-Key` on AVATEKST** (`registry/platforms/<id>.yml` väli `apiKey`).
  ADR-004 "ainult SHA-256" reegel jäeti maha, sest Ruuteri avaldisemootoris ei ole
  räsimisfunktsiooni. **Seetõttu on võtmed image'i kihtides** — ja seega image-registris, SBOM-is ja
  trivy skaneeringutes. Runtime-mount'i ei ole (ADR-011 §3), seega saladuse hoidmise vastutus on
  image'i buildimise ja image-registri ligipääsu juures (kes näeb image'it, näeb võtmeid);
  ADR-011 §2 allkiri muudaks võtme **võltsimise** tuvastatavaks, aga mitte **loetamatuks**.
  `registry` image'i porti ei tohi
  kunagi ingressi/reverse proxy kaudu avada.
- Platvormi võtme rotatsioon = commit + `registry` image'i uuesti buildimine; runtime-endpoint'i ei ole.

### 3.5 `edelivery` AS4 võtmehoidla (`own.p12`) — **Secret, mitte image'i sisu**

`edelivery` image **ei sisalda** võtmehoidlat: `docker/edelivery/Dockerfile` ei kopeeri `code/certs`-i
ja repo juure `.dockerignore` välistab selle. Ilma mount'itud failita **`edelivery` ei käivitu**
(`KeyManager` loeb faili konstruktoris, vt `code/edelivery/src/edelivery/KeyManager.kt`). Repos olev
`code/certs/own.p12` on **dev-võti** (`CN=EU-EE`, parool `changeit`), mida kasutab ainult
`compose.yml` — seda ei tohi üheski teises keskkonnas kasutada.

| Saladus | Tarbija | Otstarve | Dev väärtus | Märkus |
|---|---|---|---|---|
| `own.p12` (fail) | `edelivery`, loetakse asukohast `$KEYSTORE_DIR/own.p12` (vaikimisi `KEYSTORE_DIR=certs` → töökataloogis `/app/certs/own.p12`) | Värava enda AS4 privaatvõti + sertifikaat: **allkirjastab** väljuvad sõnumid ja **dekrüpteerib** sissetulevad | `code/certs/own.p12` (compose mount `./code/certs:/app/certs:ro`) | Keskkonnapõhine, iga keskkond oma võtmepaar |
| `KEYSTORE_PASSWORD` | `edelivery` env | Võtmehoidla **ja** privaatvõtme parool (kood kasutab sama väärtust mõlemaks) | `changeit` | Kood langeb puudumisel tagasi väärtusele `changeit` — prod-is sea alati |

#### Mida fail peab sisaldama

- PKCS#12, üks kirje: privaatvõti + selle X.509 sertifikaat (RSA; dev kasutab 2048-bitist).
- **Kirje alias (`friendlyName`) = `OWN_GATE_ID`** (nt `EU-EE`). Kui sellist aliast pole, proovib kood
  aliast `poc` ja ilma selleta käivitus ebaõnnestub.
- Privaatvõtme parool = võtmehoidla parool = `KEYSTORE_PASSWORD`.
- Sertifikaat peab olema kehtiv (`notAfter` tulevikus): partnervärav kontrollib meie serti
  `checkValidity()`-ga ja aegunud serdiga sõnumeid ei saadeta ega võeta vastu.

#### Kust võtta

AS4 usaldus põhineb **sertifikaadi kinnitamisel registris**, mitte CA-ahelal. Iga värav hoiab partneri
avalikku serti oma `registry/gates/<partneri-id>.yml` väljas `eDeliveryCert`; selle vastu kontrollitakse
partneri allkirja ja selle võtmega krüpteeritakse partnerile saadetav sõnum. Seega:

1. **Võtmepaari genereerib värava operaator ise** (soovitatavalt turvalises keskkonnas või
   HSM-is/KMS-is, mitte arendaja masinas). Privaatvõti ei lahku sellest keskkonnast muul kujul kui
   `own.p12` Secretina.
2. **Sertifikaat:**
   - Tehniliselt sobib ka isesigneeritud sert, sest partnerid kinnitavad selle registris.
   - Kui riiklik või partneritega kokku lepitud poliitika nõuab CA väljastatud serti (nt EL-i
     usaldusnimekirjas olevalt usaldusteenuse osutajalt), esita CSR sellele CA-le.
   - **Repo seda CA-d ei määra** — see on operaatori/partneritega kokkuleppe küsimus ja tuleb enne
     tootmist otsustada.

Näide, mis järgib sama kuju nagu `code/certs/generate.sh`:

```bash
OWN_GATE_ID=EU-EE                     # selle keskkonna värava id
KEYSTORE_PASSWORD='<tugev juhuslik parool>'

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out own.key

# a) isesigneeritud
openssl req -new -x509 -key own.key -out own.crt -days 730 -subj "/CN=$OWN_GATE_ID"
# b) või CA kaudu: openssl req -new -key own.key -out own.csr -subj "/CN=$OWN_GATE_ID"
#    → saada own.csr CA-le, saad tagasi own.crt (PEM)

openssl pkcs12 -export -out own.p12 -inkey own.key -in own.crt \
  -name "$OWN_GATE_ID" -passout pass:"$KEYSTORE_PASSWORD"

# kontroll: alias ja kehtivus
openssl pkcs12 -in own.p12 -passin pass:"$KEYSTORE_PASSWORD" -nokeys -info | grep friendlyName
openssl x509 -in own.crt -noout -subject -enddate
```

Pärast seda **hävita `own.key` lahtine koopia** — privaatvõti elab edasi ainult `own.p12`-s Secretis.

#### Kuhu lisada — kolm kohta, mis peavad kokku sobima

1. **Secret-hoidla (SSM Parameter Store / Vault) → Kubernetes Secret** (devops-repo, vt §7):
   - SSM-is: `/efti/<env>/edelivery/keystore` (fail **base64-kujul**) ja
     `/efti/<env>/edelivery/keystore-password`.
   - `ExternalSecret` komponeerib neist Secreti `efti-edelivery-keystore` võtmetega `own.p12`
     (`decodingStrategy: Base64`, nagu `ljvis2-devops`-i `xtr-keystore`) ja `KEYSTORE_PASSWORD`.
   - Ilma ExternalSecretita (nt käsitsi testkeskkonnas):
     ```bash
     kubectl -n efti create secret generic efti-edelivery-keystore \
       --from-file=own.p12=./own.p12 --from-literal=KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD"
     ```
2. **`edelivery` Deployment** (devops-repo `edelivery` wrapper-chart) — mount'i fail ja anna parool:
   ```yaml
   env:
     - name: KEYSTORE_DIR
       value: /app/certs
     - name: KEYSTORE_PASSWORD
       valueFrom:
         secretKeyRef: {name: efti-edelivery-keystore, key: KEYSTORE_PASSWORD}
   volumeMounts:
     - name: as4-keystore
       mountPath: /app/certs
       readOnly: true
   volumes:
     - name: as4-keystore
       secret:
         secretName: efti-edelivery-keystore
         items: [{key: own.p12, path: own.p12}]
         defaultMode: 0444   # konteiner jookseb mitte-root kasutajana `user`
   ```
3. **Avalik sert registrisse:**
   - `own.crt` PEM-sisu läheb selle keskkonna `registry/gates/<OWN_GATE_ID>.yml` väljale
     `eDeliveryCert` (YAML literaalplokina `|`).
   - **Sama PEM tuleb anda igale partnervärava operaatorile**, et nad paneksid selle oma registrisse.
     Ilma selleta ei saa partner meie allkirja kontrollida ega meile krüpteeritud sõnumit saata.
   - Kui keskkonnas on loopback-testvärav (nagu dev-i `EU-MOCK`), saab see sama serdi.
   - Pärast muutmist ehita `registry` image uuesti (vt §3.4).

#### Kontroll pärast paigaldust

`edelivery` logib käivitusel rea `partyId: <OWN_GATE_ID>, KeyIdentifier/SKI: …, SerialNumber: …`.
`SerialNumber` peab võrduma käsu `openssl x509 -in own.crt -noout -serial` väärtusega (see on
heksakujul, logis kümnendkujul). Kui rida puudub ja pod jääb taaskäivituste tsüklisse, on põhjus
tavaliselt üks järgmistest:

- fail puudub;
- vale `KEYSTORE_PASSWORD`;
- alias ei ole `OWN_GATE_ID`.

#### Rotatsioon (sert aegub või võti lekib)

Partnerid kontrollivad meid **oma registris oleva serdi järgi**, seega vahetus tuleb koordineerida:

1. Genereeri uus võtmepaar ja `own.p12`.
2. Saada uus `own.crt` partneritele ning lepi kokku vahetuse aeg.
3. Samal ajal:
   - uuenda Secret (SSM → ExternalSecret);
   - uuenda oma `registry/gates/<OWN_GATE_ID>.yml` `eDeliveryCert`;
   - ehita ja paigalda `registry` image;
   - taaskäivita `edelivery`.
4. Partnerid uuendavad oma registri.

Vahepealsel ajal ebaõnnestuvad sõnumid nende partneritega, kelle registris on veel vana sert. Võtme
lekke korral on see aken paratamatu (vt ADR-011 lahtine küsimus 4 — kiire release'i protsess).

---

## 4. Kaks eraldi andmebaasi — ära aja segi

```
database        (PostgreSQL 18, DB "efti")  ← gate'i enda andmed: kasutajad, saadetised, audit
                                                (registrid EI ole enam siin — vt §3.4, ADR-011 §4)
tim-database     (PostgreSQL 18, DB "tim")   ← TIM'i sessioonid/kasutajad (autentimise siseasi)
```

Kummalgi oma parool, oma ühendusstring, oma migratsioonid (TIM ise teeb `auto_migrate: true`
`tim.yaml` kaudu — see EI KÄI Liquibase'i läbi).

---

## 5. Liquibase kontekst — ära lase dev-seemet prod-i

Ainus `context:dev` changeset on `20260813-auth-seed.sql` (dev-kasutajad, sh Super Admin
`60001019906`). Väravate/platvormide/asutuste seemneid andmebaasis enam ei ole — need on
`registry/` failid (ADR-011, vt §3.4), ja **dev-fixture'id (sh `mock-secret-key`) jõuavad tootmisse
`registry` image'i kaudu, kui `registry/` jäetakse enne buildi asendamata**.

`DSL/Liquibase/liquibase.properties`-is on hetkel `liquibase.contexts: dev` — kui see jääb
prod-paigaldusse muutmata, jõuavad dev-kasutajad tootmisandmebaasi. Sea prod-is
`LIQUIBASE_COMMAND_CONTEXTS` (või `--contexts=`) väärtusele, mis `dev`-i EI sisalda (nt tühi string).

---

## 6. Võrgu isolatsioon (mitte secret, aga sama kriitiline)

`INTERNAL_SERVICE_TOKEN` (§3.2) on ainus kaitse `/xroad/**` ja `/efti/api/v1/authority/**` peal —
need marsruudid jagavad Ruuteri porti 8086 avaliku gate-API-ga. Ingress/reverse-proxy **EI TOHI**
neid avalikult eksponeerida; ainult X-Road Turvaserver tohib `/xroad/**`-ni jõuda. Vt ADR-006.

`DSL/Ruuter/ops/**` (saadetiste arhiveerimine, §3.2 `ARCHIVE_OPS_TOKEN`) jagab samuti Ruuteri porti
8086, aga pole `docker/ui/nginx.conf` kaudu proksitud (vt "Nginx proxy" `AGENTS.md`-is) — see pole
ise kaitse, ingress/reverse-proxy peab `/ops/**` eraldi blokeerima, ligipääs ainult
CronManager'ilt konteinerivõrgu siseselt. Vt `docs/architecture/infrastructure/append_only_archival.md`
ja `DSL/CronManager/efti-ops.yaml`.

---

## 7. Sekundid GitLab CI + devops repo jaoks (viide, mitte täpne retsept)

Selle repo [`.gitlab-ci.yml`](../../../.gitlab-ci.yml) on juba `ljvis-2` mustri järgi kohandatud
(image-build iga teenuse jaoks, SBOM, trivy, release-pin `services/efti/devops` peale) — vt faili
enda päisekommentaari nõutavate CI/CD muutujate kohta (`HARBOR_URL`, `HARBOR_USERNAME`,
`HARBOR_PASSWORD`, `SERVICECODE=efti`, `SONAR_HOST_URL`, `SONAR_TOKEN`, `GITOPS_TOKEN`).

Puudu on veel kaasnev **`services/efti/devops` Helm-chartide repo** (nagu `ljvis2-devops` selles
töökeskkonnas) — see repo pole selle sessiooni käes ja jääb eraldi tööks. Kui see luuakse, on
`ljvis2-devops`-i muster otse üle kantav:

- `charts/ljvis2-config/templates/externalsecret.yaml` stiilis kohandatav `ExternalSecret` template
  (`external-secrets.io`), mis loeb SSM Parameter Store'ist (`ssmPrefix: /efti/<env>`) ja komponeerib
  §3 tabeli saladused K8s Secretideks `envFrom` kaudu.
- Iga §3 rida vastab ühele SSM parameetrile (nt `/efti/dev/db/main/efti/password`,
  `/efti/dev/tim/admin-token`, `/efti/dev/tim/jwt-private-key` — viimane base64+`decodingStrategy:
  Base64`, samamoodi nagu `ljvis2-devops`-i `xtr-keystore`).
- `resources:` iga chardi `values.yaml`-is §1 tabeli "Soovituslik prod" veeru järgi.

Vastutus: infra/admin, kes teeb esmapaigalduse, koostab `services/efti/devops` repo selle malli
järgi ja seemendab SSM (analoogselt `ljvis2-devops`'i `scripts/seed-ssm.sh`-ile).
