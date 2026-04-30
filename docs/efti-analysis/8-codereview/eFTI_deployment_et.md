# Paigaldamise juhend

| | |
|---|---|
| **Autor** | Sten Viljus |
| **Ettevõte** | Askend Estonia OÜ |
| **Kontakt** | sten.viljus@askend.com |

## Ülevaade

See dokument kirjeldab eFTI Gate paigaldamist, seadistamist ja testbed'i ülesseadmist. Dokument katab:
- Süsteeminõuded ja eeltingimused
- Paigaldamine Docker Compose'iga (VPS / server)
- Paigaldamine Kubernetes'esse (Helm chart)
- Testbed'i ülesseadmine (mitme gate'i keskkond)
- Teiste riikide eFTI gate'ide ühendamise nõuded

---

## Süsteeminõuded

### Minimaalsed riistvaranõuded

| Komponent | Miinimum | Soovituslik |
|-----------|----------|-------------|
| CPU | 1 vCPU | 2+ vCPU |
| RAM | 1 GB | 4+ GB |
| Ketas | 10 GB | 20+ GB |
| Võrk | Avalik IP, pordid 80/443 | Staatiline IP |

Gate tarkvara on väga efektiivne — üks node teenindab jõudlustestide põhjal kuni 100 req/s kõigi operatsioonitüüpide jaoks paralleelselt.

### Tarkvaranõuded

| Tarkvara | Versioon | Märkus |
|----------|----------|--------|
| Docker | 24+ | Koos Docker Compose v2-ga |
| SSH | — | Ligipääs serverile |
| Domeeninimi | — | HTTPS sertifikaadi jaoks |

Arenduskeskkonna jaoks lisaks:
- Java 25+
- Node.js 24+
- IntelliJ IDEA (soovituslik)

---

## Paigaldamine Docker Compose'iga

> **NB:** Docker Compose on sobilik **arenduskeskkondadele ja PoC testbed'ile**. Tootmispaigalduseks soovitame kasutada versioonitagiga Docker image'e container registry'st (vt allpool "Soovituslik tootmispaigaldus").

### 1. Serveri ettevalmistamine

```sh
# Docker paigaldamine (Ubuntu/Debian)
curl -fsSL https://get.docker.com | sh

# Kataloogi loomine
mkdir -p ~/efti-gate-poc
cd ~/efti-gate-poc
```

### 2. Sertifikaatide genereerimine

eDelivery AS4 suhtluseks on vaja RSA sertifikaati. Sertifikaat identifitseerib gate'i teistele gate'idele.

```sh
# generate-certificates.sh genereerib:
# - certs/own.key  — privaatvõti
# - certs/own.crt  — sertifikaat (PEM)
# - certs/own.p12  — PKCS12 keystore (parool: changeit)
./generate-certificates.sh gate
```

Sertifikaadi CN (Common Name) peab vastama gate'i `GATE_ID` väärtusele.

Tootmiskeskkonnas tuleb kasutada usaldatud sertifikaate (mitte self-signed).

### 3. Konfiguratsioon

Loo `.env` fail:

```env
ENV=prod

# Gate identifikaator (unikaalne eFTI võrgustikus)
GATE_ID=eu-ee31
COUNTRY=EE

# Andmebaasi ühendus
DB_URL=jdbc:postgresql://db/efti
DB_USER=efti
DB_PASS=<tugev_parool>
DB_APP_PASS=<tugev_parool>
DB_POOL_SIZE=180
```

### 4. Docker Compose failid

Tootmiskeskkonna jaoks kasutatakse kahte compose faili:

- `compose.yml` — põhikonfiguratsioon (gate, demo-platform, db)
- `compose.server.yml` — serveri lisad (Caddy reverse proxy, restart policy, volumes)

```sh
# Käivitamine
GATE_ID=eu-ee31 docker compose -f compose.yml -f compose.server.yml up -d --wait
```

### 5. Caddy reverse proxy

`compose.server.yml` seadistab Caddy reverse proxy automaatselt Docker label'ite kaudu:

- Automaatne HTTPS (Let's Encrypt)
- Gate: `https://<GATE_ID>.eftisandbox.eu`
- Demo platform: `https://demo-platform.<GATE_ID>.eftisandbox.eu`

DNS A-kirje peab viitama serveri IP-le.

### 6. Kontrollimine

```sh
# Health check
curl https://<GATE_ID>.eftisandbox.eu/health

# Admin UI
# Brauseris: https://<GATE_ID>.eftisandbox.eu/

# OpenAPI
# https://<GATE_ID>.eftisandbox.eu/api/openapi
# https://<GATE_ID>.eftisandbox.eu/v1/openapi
```

### 7. Deploy uuendamine (PoC / arendus)

Praegune deploy skript (ainult PoC/arenduskasutuseks):

```sh
# deploy.sh teeb automaatselt:
# 1. Ehitab ja testib (gradlew test jar, npm test + build)
# 2. Ehitab Docker image'd
# 3. Saadab image'd serverisse (docker save | ssh | docker load)
# 4. Salvestab olemasolevad logid
# 5. Taaskäivitab konteinerid
./deploy.sh eu-ee31
```

---

## Soovituslik tootmispaigaldus

Docker Compose + `deploy.sh` on sobiv arenduskeskkondadele, aga **tootmis- ja testbed-paigalduseks** on soovituslik kasutada versioonitagiga Docker image'e container registry'st.

### Miks mitte Docker Compose tootmises

| Probleem | Kirjeldus |
|----------|-----------|
| **Versioon pole jälgitav** | `docker save \| ssh \| docker load` ei jäta jälge, milline versioon on serveris |
| **Rollback puudub** | Eelmise versiooni taastamine nõuab uut deploy'd |
| **Zero-downtime puudub** | `docker compose up` peatab vana konteineri enne uue käivitamist |
| **Image pole jagatav** | Teised osapooled (testbed'i partnerid) ei saa image't kätte |
| **Reprodutseeritavus puudub** | Ehitus toimub arendaja masinas, mitte CI pipeline'is |

### Soovituslik deploy voog

```
Git push → CI (GitHub Actions) → test → build → tag → push registry → deploy
```

**1. Image tagimine:**

Iga image peab olema tagitud unikaalse identifikaatoriga:
- **Git commit hash** — `ghcr.io/kemit-ee/efti-gate-poc:a1b2c3d`
- **Semver tag** — `ghcr.io/kemit-ee/efti-gate-poc:1.2.3`
- **Kuupäev** — `ghcr.io/kemit-ee/efti-gate-poc:2026-03-10`

`latest` tagi kasutamine tootmises on **keelatud** — see ei anna infot, milline versioon tegelikult töötab.

**2. Container Registry:**

| Registry | Märkus |
|----------|--------|
| GitHub Container Registry (ghcr.io) | Tasuta avalikele projektidele, integreeritub GitHub Actions'iga |
| AWS ECR | Kui infrastruktuur on AWS-is |
| Docker Hub | Universaalne, aga privaatsete image'de jaoks tasuline |

**3. Deploy serverile:**

```sh
# Tootmises (image registrist, konkreetne versioon)
docker pull ghcr.io/kemit-ee/efti-gate-poc:1.2.3
docker stop efti-gate && docker rm efti-gate
docker run -d --name efti-gate \
  --restart unless-stopped \
  --env-file /etc/efti-gate/.env \
  -v /etc/efti-gate/certs:/app/certs:ro \
  -p 8080:8080 \
  ghcr.io/kemit-ee/efti-gate-poc:1.2.3
```

Või Kubernetes'es (eelistatud):
```sh
helm upgrade efti-gate charts/efti-gate \
  --set image.tag=1.2.3 \
  -f values-prod.yaml
```

**4. Rollback:**

```sh
# Docker
docker run ... ghcr.io/kemit-ee/efti-gate-poc:1.1.0  # eelmine versioon

# Kubernetes
helm rollback efti-gate 1
```

---

## Paigaldamine Kubernetes'esse

### Eeltingimused

- Kubernetes klaster (1.24+)
- Helm 3+
- Ingress Controller (nginx-ingress, Traefik või AWS ALB)
- PostgreSQL andmebaas (RDS, CloudNativePG operaator vms)
- Container Registry (ghcr.io, ECR vms)

### 1. Image ehitamine ja push

```sh
# Image ehitamine
docker build -f gate/Dockerfile -t <registry>/efti-gate-poc:latest .

# Push registrisse
docker push <registry>/efti-gate-poc:latest
```

### 2. Kubernetes Secret'id

```sh
# Andmebaasi paroolid
kubectl create secret generic efti-gate-rds \
  --from-literal=password=<DB_PASS> \
  --from-literal=appPassword=<DB_APP_PASS>

# eDelivery sertifikaadid
kubectl create secret generic efti-gate-certs \
  --from-file=own.crt=gate/certs/own.crt \
  --from-file=own.key=gate/certs/own.key
```

### 3. Helm values

Loo `values-prod.yaml`:

```yaml
image:
  repository: <registry>/efti-gate-poc
  tag: "latest"

env:
  ENV: "prod"
  GATE_ID: "eu-ee31"
  COUNTRY: "EE"

rds:
  enabled: true
  host: "<db-host>"
  port: 5432
  database: efti
  username: efti
  existingSecret:
    name: efti-gate-rds

certs:
  enabled: true
  existingSecret:
    name: efti-gate-certs

ingress:
  enabled: true
  className: nginx
  hosts:
    - host: eu-ee31.eftisandbox.eu
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: efti-gate-tls
      hosts:
        - eu-ee31.eftisandbox.eu

resources:
  requests:
    cpu: 200m
    memory: 512Mi
  limits:
    cpu: 2
    memory: 2Gi
```

### 4. Paigaldamine

```sh
helm install efti-gate charts/efti-gate -f values-prod.yaml
```

---

## Testbed'i ülesseadmine

Testbed on mitme gate'i keskkond, kus saab testida gate'idevahelist suhtlust (identifier broadcast, remote dataset query, follow-up sõnumid).

### Lokaalne testbed (arendamiseks)

`multiple-gates.sh` käivitab mitu gate'i Docker'is ühises võrgus:

```sh
# Käivitamine (3 gate'd: estlandia, latveria, lithonia)
./multiple-gates.sh start

# Peatamine
./multiple-gates.sh stop
```

Skript:
1. Ehitab Docker image'd
2. Loob jagatud Docker võrgu (`efti_gate_test`)
3. Käivitab iga gate'i eraldi Docker Compose projektina
4. Registreerib gate'd omavahel (`add-gate.sh`)

Pordid:
- Gate 1: `http://localhost:8081`
- Gate 2: `http://localhost:8082`
- Gate 3: `http://localhost:8083`

### Serveripõhine testbed

Testbed'i saab seadistada ka eraldi serverile (nt VPS), kus jookseb mitu gate'i instansi. See on vajalik realistliku võrgulatentsusega testimiseks.

#### Variant A: mitu VPS-i

Iga gate'i jaoks eraldi VPS. See simuleerib reaalset olukorda, kus gate'd asuvad eri riikides.

```
VPS 1 (Hetzner, Saksamaa):  eu-ee31.eftisandbox.eu  — Eesti gate
VPS 2 (Contabo, Prantsusmaa): eu-test1.eftisandbox.eu — Test gate 1
VPS 3 (muu pakkuja):          eu-test2.eftisandbox.eu — Test gate 2
```

Iga VPS seadistatakse nagu ülalpool kirjeldatud (Docker Compose + Caddy).

#### Variant B: üks server, mitu instansi

Ühe serveri peal mitu Docker Compose projekti:

```sh
# Gate 1
cd /opt/efti-gate-1
GATE_ID=eu-ee31 docker compose -f compose.yml -f compose.server.yml up -d

# Gate 2
cd /opt/efti-gate-2
GATE_ID=eu-test1 docker compose -f compose.yml -f compose.server.yml up -d
```

NB: iga instansi jaoks peab olema eraldi `.env` (erinevad `GATE_ID`, `DB_PASS`, sertifikaadid) ja eraldi andmebaas.

### Gate'ide omavahel ühendamine

Pärast gate'ide käivitamist tuleb need omavahel registreerida. Seda saab teha Admin UI kaudu või API-ga:

```sh
# Gate 2 registreerimine Gate 1-s
curl -X POST https://eu-ee31.eftisandbox.eu/api/gates \
  -H "Authorization: Basic <admin_credentials>" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "eu-test1",
    "countryCode": "XX",
    "eDeliveryUrl": "https://eu-test1.eftisandbox.eu/services/msh",
    "eDeliveryCert": "<PEM sertifikaat>"
  }'
```

Registreerimisel on vaja:
- **id** — teise gate'i unikaalne identifikaator
- **countryCode** — riigikood
- **eDeliveryUrl** — eDelivery MSH endpoint URL
- **eDeliveryCert** — teise gate'i eDelivery sertifikaat (PEM formaat)

---

## Teiste riikide eFTI gate'ide ühendamine

### Nõuded teistele gate'idele

Selleks, et teise riigi eFTI gate saaks meie testbed'is töötada, peab see vastama järgmistele nõuetele:

#### Pakendamise ja opereerimise nõuded

Kui teise riigi gate paigaldatakse **meie hallatavasse testbed'i** (st meie serverile), siis kehtivad järgmised nõuded:

| # | Nõue | Kirjeldus |
|---|------|-----------|
| 1 | **Docker image** | Gate tarkvara peab olema pakendatud Docker image'ina. See tagab reprodutseeritavuse, isolatsiooni ja lihtsa paigalduse |
| 2 | **Container registry** | Image peab olema kättesaadav container registry'st (Docker Hub, ghcr.io, ECR vms). `docker save` failina saatmine **ei ole aktsepteeritav** tootmiskeskkonnas |
| 3 | **Versiooni tag** | Image peab olema tagitud konkreetse versiooniga (semver, commit hash või kuupäev). `latest` tag ei ole piisav — peab olema võimalik identifitseerida täpne versioon |
| 4 | **Health check endpoint** | HTTP(S) endpoint (nt `/health`), mis tagastab 200 OK, kui gate on valmis liiklust vastu võtma. Vajalik automatiseeritud monitoorimiseks |
| 5 | **Konfiguratsioon env muutujatega** | Gate peab olema konfigureeritav keskkonnamuutujatega (port, andmebaas, sertifikaadid jne). Hardcoded konfiguratsioon image'i sees ei sobi |
| 6 | **Non-root kasutaja** | Konteiner peab jooksma non-root kasutajana (turvalisus) |
| 7 | **Eraldi andmebaas** | Kui gate vajab andmebaasi, peab see olema konfigureeritav välise ühendusena (mitte image'i sisse ehitatud). Toetatud peab olema PostgreSQL |
| 8 | **Dokumentatsioon** | Kaasas peab olema README, mis kirjeldab: kõik keskkonnamuutujad, nõutavad mahud (volumes), pordid, andmebaasi nõuded ja käivitusjuhend |

Kui teise riigi gate töötab **nende oma serveris** (mitte meie testbed'is), siis pakendamise nõuded ei kehti — piisab alljärgnevatest protokollinõuetest.

#### Protokollinõuded (kõigile gate'idele)

| # | Nõue | Kirjeldus |
|---|------|-----------|
| 1 | **eDelivery AS4 endpoint** | HTTPS endpoint, mis vastab AS4 protokollile (`/services/msh` või sarnane). Peab toetama MIME multipart sõnumeid |
| 2 | **eDelivery sertifikaat** | RSA X.509 sertifikaat (PEM formaat). Kasutatakse sõnumite krüpteerimiseks ja saatja tuvastamiseks |
| 3 | **TLS sertifikaat** | Kehtiv HTTPS sertifikaat endpoint'il (Let's Encrypt või muu usaldatud CA) |
| 4 | **Avalik HTTPS endpoint** | Endpoint peab olema kättesaadav avalikust internetist (port 443) |
| 5 | **eFTI XML skeemid** | Sõnumite formaat peab vastama eFTI XML skeemidele (`xsd/` kataloog) |
| 6 | **Unikaalne Party ID** | eDelivery sõnumites kasutatav unikaalne identifikaator |

#### Toetatud kommunikatsiooniprotokollid

| Protokoll | Kirjeldus | Kohustuslik |
|-----------|-----------|-------------|
| **eDelivery AS4** | Standardne eFTI gate'idevaheline suhtlus. SOAP/MIME, krüpteeritud (AES-GCM + RSA-OAEP) | Jah |
| **Fast Adapter (REST)** | Alternatiivne kiire REST-põhine suhtlus eFTI Gate PoC gate'ide vahel. `X-API-Key` autentimine | Ei (ainult PoC gate'ide vahel) |

#### Toetatud operatsioonid

Teise riigi gate peab toetama vähemalt järgmisi operatsioone:

| # | Operatsioon | XML root tag | Kirjeldus |
|---|-------------|--------------|-----------|
| 1 | **Identifier query** | `identifierQuery` → `identifierResponse` | Identifier'ite otsing. Gate peab vastama oma identifier'itega |
| 2 | **Dataset query (UIL)** | `uilQuery` → `uilResponse` | Dataset'i pärimine UIL järgi. Gate peab edastama päringu platvormile |
| 3 | **Follow-up** | `postFollowUpRequest` | Follow-up sõnumi edastamine platvormile |
| 4 | **Ping** | ebXML test action | Gate kättesaadavuse kontroll |

#### eDelivery sõnumi formaat

Sõnumid vahetatakse AS4 formaadis:
- **Transport:** HTTPS POST, `multipart/related` (SOAP envelope + krüpteeritud payload)
- **Krüpteerimine:** AES-128-GCM (payload) + RSA-OAEP SHA-256 (sümmeetriline võti)
- **Pakkimine:** GZIP
- **Identifitseerimine:** WS-Security KeyIdentifier (SKI)

#### Teise gate'i registreerimise sammud

1. **Sertifikaatide vahetus** — mõlemad osapooled vahetavad eDelivery sertifikaadid (PEM). TLS sertifikaate ei pea vahetama, kui need on avaliku CA poolt väljastatud
2. **Gate registreerimine** — Admin UI kaudu või API-ga (vt ülalpool)
3. **Ping test** — kontrollida, et gate reageerib (Admin UI näitab gate'i staatust ONLINE/OFFLINE)
4. **Identifier query test** — testida identifier'ite otsingut
5. **Dataset query test** — testida dataset'i pärimist

### Teadaolevad ühilduvusteemad

| Probleem | Kirjeldus | Lahendus |
|----------|-----------|----------|
| **Erinevad access point'd** | Mõned riigid kasutavad Domibus, Harmony, CData Arc jms. Formaat peab vastama AS4 standardile | Testida konkreetse access point'iga |
| **Sertifikaatide formaat** | Mõnel gate'il on sertifikaat DER formaadis, mitte PEM | Konverteerida: `openssl x509 -inform DER -in cert.der -outform PEM -out cert.pem` |
| **Krüpteerimismeetodid** | Standardist erinev krüpteerimine logitakse hoiatusena, aga sõnum proovitakse dekrüpteerida | Kontrollida logidest hoiatusi |
| **Aegumine / timeout** | Teise riigi gate võib aeglaselt vastata | eDelivery timeout on konfigureeritav: `EDELIVERY_TIMEOUT_SECONDS` (vaikimisi 60s) |

---

## Konfiguratsioon

### Keskkonna muutujad

| Muutuja | Kirjeldus | Vaikeväärtus | Kohustuslik |
|---------|-----------|--------------|-------------|
| `ENV` | Keskkond (`dev`, `demo`, `prod`) | `dev` | Jah |
| `GATE_ID` | Gate'i unikaalne identifikaator | `POC` | Jah |
| `COUNTRY` | Riigikood (ISO 3166-1 alpha-2) | `EE` | Jah |
| `DB_URL` | PostgreSQL JDBC URL | — | Jah |
| `DB_USER` | Andmebaasi kasutaja | `efti` | Jah |
| `DB_PASS` | Andmebaasi parool | — | Jah |
| `DB_APP_PASS` | Piiratud õigustega `app` kasutaja parool | — | Jah |
| `DB_POOL_SIZE` | Ühenduste pool suurus | `180` | Ei |
| `DB_MIGRATE` | Migratsioonide keelamine (`no`) | — | Ei |
| `PORT` | HTTP port | `8080` | Ei |
| `KEYSTORE_DIR` | Sertifikaatide kataloog | `certs` | Ei |
| `KEYSTORE_PASSWORD` | PKCS12 keystore parool | `changeit` | Ei |
| `EDELIVERY_TIMEOUT_SECONDS` | eDelivery päringu timeout | `60` | Ei |
| `OWN_PARTY_ID` | eDelivery Party ID (automaatselt = GATE_ID) | — | Ei |

### Andmebaasi skeem

Andmebaasi migratsioonid jooksevad automaatselt käivitusel (`DBMigrator`). Migratsioonifailid asuvad `gate/db/` kataloogis.

Käivitusel luuakse automaatselt ka piiratud õigustega `app` kasutaja (`gate/db/app_user.sql`), mida rakendus kasutab pärast migratsioone.

---

## Veaotsing

### Gate ei käivitu

```sh
# Logide vaatamine
docker compose logs gate

# Health check
curl http://localhost:8080/health
```

Levinud põhjused:
- Andmebaas pole kättesaadav (`DB_URL` vale)
- Sertifikaadid puuduvad (`certs/own.p12` ei eksisteeri)
- Port on juba kasutusel

### Gate'idevaheline suhtlus ei toimi

1. Kontrollida, et mõlemad gate'd on käivitunud (`/health`)
2. Kontrollida, et gate'd on omavahel registreeritud (Admin UI → Gates)
3. Kontrollida gate'i staatust (ONLINE/OFFLINE) — ping job kontrollib iga 5 minuti tagant
4. Kontrollida logidest eDelivery vigu (`Error handling message`, `Could not ping gate`)
5. Kontrollida sertifikaatide vastavust — registreeritud sertifikaat peab vastama teise gate'i tegelikule sertifikaadile

### Sertifikaatide probleemid

```sh
# Sertifikaadi info vaatamine
openssl x509 -in gate/certs/own.crt -text -noout

# SKI (Subject Key Identifier) vaatamine — see logitakse käivitusel
openssl x509 -in gate/certs/own.crt -noout -ext subjectKeyIdentifier
```
