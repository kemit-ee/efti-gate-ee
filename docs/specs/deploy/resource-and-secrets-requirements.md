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
| `resql` (ReSql 0.3.0-alpha) | JAH | — (määramata!) | — (määramata!) | 0.5 vCPU / 512 MiB | max_connections 75 (resql.yaml) — jäta PG max_connections=100 juurde varu teistele klientidele. Kaks andmeallikat: `efti` ja `archive` |
| `ruuter` (Ruuter 0.9.15-rc, `/efti` + `/xroad`) | JAH | 2.0 | 512 MiB | 1 vCPU / 512 MiB (koormustestitud, vt performance-dok) | — |
| `ruuter-xroad-mock` (avalik X-Roadi arendaja-mokk, `/developer/**`) | VALIKULINE | 0.25 | 128 MiB | 0.25 vCPU / 128 MiB | Standalone, andmebaasita — vt `docs/developer/x_road_developer_mock.md`. Jäta välja, kui väliseid platvormiarendajaid ei teenindata. |
| `tim` (Token & Identity Manager 0.3.0-alpha) | JAH | — | — | 0.5 vCPU / 256 MiB | Oma PostgreSQL DB (vt §3) |
| `tim-database` (PostgreSQL 18, `tim` DB) | JAH | — | — | 0.5 vCPU / 512 MiB | Stateful, eraldi instants(dev)/eraldi skeem-või-instants (prod, vali) |
| `ui` (nginx + Vite/Svelte build) | JAH | — | — | 0.25 vCPU / 128 MiB | Staatiline sisu, väga kerge |
| `edelivery` (Kotlin/klite, AS4 G2G) | JAH | — | 512 MiB | 0.5 vCPU / 512 MiB | — |
| `xml-mapper` (Kotlin/klite, XML↔JSON) | JAH | — | 512 MiB | 0.5 vCPU / 512 MiB | — |
| `multiplexer` (Kotlin/klite, otsingu fan-out) | JAH | — | 512 MiB | 0.5 vCPU / 512 MiB | — |
| `pubsub` (Kotlin/klite, väravate/platvormide muudatuste levitus) | JAH | — | 128 MiB | 0.25 vCPU / 128 MiB | — |
| `liquibase` (migratsioonijooksja) | JAH, ühekordne | — | — | 0.5 vCPU / 256 MiB | Job/init-container, mitte pikaajaline teenus |
| `tara-mock` | EI (ainult dev/test) | — | — | — | Tootmises kasutatakse päris TARA teenust (vt §4) |

**Tähelepanek:** `resql`, `tim`, `tim-database`, `ui`, `xml-mapper`, `multiplexer`, `pubsub`-il
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
| `OWN_GATE_ID` | Selle värava X-Road/eFTI identifikaator | `EU-EE` |
| `RUUTER_URL`, `RESQL_URL`, `EDELIVERY_URL`, `XML_MAPPER_URL`, `MULTIPLEXER_URL`, `PUBSUB_URL`, `TIM_URL` | Teenustevahelised URL-id (K8s-is Service DNS-nimed) | `http://<service>:<port>` |
| `LOGGER_CLASS` | Kotlin-teenuste logimistase/formaat | määramata (vaikimisi) |
| `VITE_USE_PROD_TARA_URL` | UI build-arg, kas UI viitab reaalsele TARA-le | `true` (image-build juba seab) |

---

## 3. Saladused (secrets) — mis, kus kasutusel, kuidas paigaldada

Iga rida: **nimi** → **kes tarbib** (konteiner/env muutuja) → **otstarve** → **dev väärtus (ÄRA
kasuta prod-is)** → **paigalduse märkus**.

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
| `INTERNAL_SERVICE_TOKEN` | Ruuter (`constants.ini`, `[#INTERNAL_SERVICE_TOKEN]`); saadetakse `X-Internal-Service-Token` päisena | Jagatud saladus gate-sisesteks kõnedeks (X-Roadi adapter → `efti/api/v1/authority/**`, edaspidi ka G2G sissetulev). **Selle valdaja saab ADMIN-taseme ligipääsu kõigile authority-marsruutidele.** | `dev-internal-service-token-change-me` | **ÄRA UNUSTA**: fail on `docker/ruuter/Dockerfile`-ga image'isse `COPY`-tud, `${ENV}`-asendust `constants.ini`-s EI OLE. Väärtuse muutmiseks tuleb fail muuta ja ruuter image uuesti buildida — miski ei anna hoiatust, kui unustad. Kuni see pole lahendatud (vt ADR-006 avatud küsimused), on ainus kaitse, et `/xroad/**` ja `/efti/api/v1/authority/**` pole avalikult ligipääsetavad — vt §5 |
| `ARCHIVE_OPS_TOKEN` | Ruuter (`constants.ini`, `[#ARCHIVE_OPS_TOKEN]`), `DSL/Ruuter/ops/.guard.yml`; välise CronManager'i saadetav `Authorization: Bearer`-päring | Ainus autentimine `POST /ops/v1/archive-consignments` ja `POST /ops/v1/purge-archive` peale — literal compare, mitte JWT. **Selle valdaja saab käivitada saadetiste külmladustamise/purge'imise mistahes ajal.** Vt `docs/architecture/infrastructure/append_only_archival.md` | `dev-archive-ops-token-change-me` | Sama probleem mis `INTERNAL_SERVICE_TOKEN`-il: `constants.ini` on image'isse `COPY`-tud, `${ENV}`-asendust ei ole — muutmiseks tuleb ruuter image uuesti buildida. `DSL/Ruuter/ops/` **ei ole nginx'i kaudu proksitud** (vt §6) — ainus kaitse on, et CronManager pöördub otse konteinerivõrgus, mitte ingressi kaudu |

### 3.3 TIM (Token & Identity Manager) — **oma eraldi PostgreSQL andmebaas**

| Saladus | Tarbija | Otstarve | Dev väärtus | Märkus |
|---|---|---|---|---|
| `TIM_DATABASE_URL` | `tim` teenus | TIM'i oma PG andmebaasi ühendusstring (`postgres://user:pass@host/db`) | `postgres://tim:tim-secret@tim-database:5432/tim` | **See on TEINE andmebaas kui `efti`** — eraldi instants dev-is (`tim-database` teenus), prod-is otsusta kas eraldi instants või sama klastri teine DB |
| `TIM_ADMIN_TOKEN` | `tim` (`security.admin_token_env`, `tim.yaml`) JA Ruuter (`constants.ini` → `[#TIM_ADMIN_TOKEN]`, saadetakse `X-TIM-Admin-Token` päisena `auth/POST/{callback,logout,dev-login}.yml`-ist) | TIM'i admin-API autentimine | `dev-tim-admin-token` | **Peab kattuma mõlemas kohas** — sama väärtus kahes eri failis/teenuses |
| `TIM_TARA_CLIENT_ID` / `TIM_TARA_CLIENT_SECRET` | `tim` (`oauth2.providers.tara.client_id_env`/`client_secret_env`) | OIDC klient TARA vastu | `efti` / `efti-secret` (dev-is TARA-Mock vastu) | **Prod: reaalne TARA registreering** (RIA), mitte mock. Vt ka `allowed_redirect_uris` `tim.yaml`-is — prod domeen tuleb sinna lisada |
| TIM JWT allkirjastamisvõti | `tim` (`jwt.private_key_path: /opt/tim/keys/jwt-private.pem`, RSA, PKCS8 PEM, `key_id: efti-rs-1`) | Kasutajate sessiooni-JWT allkirjastamine | **Dev-is `docker/tim/entrypoint.sh` genereerib selle ISE**, kui faili pole (`openssl genpkey`) | **KRIITILINE PROD-NÕUE**: kui seda ei tehta persistentseks Secretiks/volumeks, genereeritakse iga taaskäivituse peale UUS võti ja kõik olemasolevad sessioonid/JWT-d muutuvad kehtetuks — kõik kasutajad logitakse välja iga podi restardi peale. Genereeri võti üks kord väljaspool konteinerit, pane K8s Secretina, mountida `/opt/tim/keys/jwt-private.pem` peale (samamoodi nagu ljvis2-devops `tim-jwt-key` ExternalSecret) |

### 3.4 Sertifikaadid — **EI OLE deploy-saladused**

`gates.e_delivery_cert` / `gates.tls_cert` ja `platforms.e_delivery_cert` / `platforms.tls_cert`
(PEM-tekstina Postgres-veerus, vt `DSL/Liquibase/initial/002-gates.sql` ja `003-platforms.sql`) on
**operatiivsed andmed, mida admin laeb üles Admini kasutajaliidese kaudu** pärast paigaldust
(Väravad / Platvormid vaated, vt `docs/user-guide/src/{gates,platforms}.md`), mitte K8s Secretid ega
midagi, mis paigalduse ajal seadistatakse. Ära neid CI/CD saladuste loendisse pane.

Samamoodi platvormi `X-Api-Key` (ADR-004) — see genereeritakse runtime'is
`POST /admin/v1/platforms/api-key/:id` kaudu, salvestatakse ainult SHA-256 räsina
(`platforms.api_key_hash`); täisvõtit näidatakse admin-liideses ühe korra ja see pole
paigaldussaladus.

---

## 4. Kaks eraldi andmebaasi — ära aja segi

```
database        (PostgreSQL 18, DB "efti")  ← gate'i enda andmed: väravad, platvormid,
                                                pädevad asutused, kasutajad, saadetised, audit
tim-database     (PostgreSQL 18, DB "tim")   ← TIM'i sessioonid/kasutajad (autentimise siseasi)
```

Kummalgi oma parool, oma ühendusstring, oma migratsioonid (TIM ise teeb `auto_migrate: true`
`tim.yaml` kaudu — see EI KÄI Liquibase'i läbi).

---

## 5. Liquibase kontekst — ära lase dev-seemet prod-i

Kuus changeset'i on märgitud `context:dev` ja käivituvad ainult siis, kui Liquibase kontekstides on
`dev`:

- `20260901-seed-own-gate.sql` — loob testvärava `EU-MOCK`
- `20260813-auth-seed.sql` — dev-kasutajad
- `20260814-mock-platform.sql` — mock-platvorm + mock-eDelivery platvorm
- `20260902-platform-api-key.sql` — **mock-platvormi API võti on `mock-secret-key`**

`DSL/Liquibase/liquibase.properties`-is on hetkel `liquibase.contexts: dev` — **kui see jääb
prod-paigaldusse muutmata, saab mock-platvorm ja tema avalikult teadaolev API võti tootmisandmebaasi
sisse**. Sea prod-is `LIQUIBASE_COMMAND_CONTEXTS` (või `--contexts=`) väärtusele, mis `dev`-i EI
sisalda (nt tühi string).

---

## 6. Võrgu isolatsioon (mitte secret, aga sama kriitiline)

`INTERNAL_SERVICE_TOKEN` (§3.2) on ainus kaitse `/xroad/**` ja `/efti/api/v1/authority/**` peal —
need marsruudid jagavad Ruuteri porti 8086 avaliku gate-API-ga. Ingress/reverse-proxy **EI TOHI**
neid avalikult eksponeerida; ainult X-Road Turvaserver tohib `/xroad/**`-ni jõuda. Vt ADR-006.

`DSL/Ruuter/ops/**` (saadetiste arhiveerimine, §3.2 `ARCHIVE_OPS_TOKEN`) jagab samuti Ruuteri porti
8086, aga pole `docker/ui/nginx.conf` kaudu proksitud (vt "Nginx proxy" `AGENTS.md`-is) — see pole
ise kaitse, ingress/reverse-proxy peab `/ops/**` eraldi blokeerima, ligipääs ainult
CronManager'ilt konteinerivõrgu siseselt. Vt `docs/architecture/infrastructure/append_only_archival.md`
ja `docs/specs/deploy/cronmanager-archive.yaml`.

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
