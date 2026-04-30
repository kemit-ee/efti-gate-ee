# Deployment Guide

| | |
|---|---|
| **Author** | Sten Viljus |
| **Company** | Askend Estonia OÜ |
| **Contact** | sten.viljus@askend.com |

## Overview

This document describes eFTI Gate installation, configuration, and testbed setup. The document covers:
- System requirements and prerequisites
- Installation with Docker Compose (VPS / server)
- Installation on Kubernetes (Helm chart)
- Testbed setup (multi-gate environment)
- Requirements for connecting other countries' eFTI gates

---

## System Requirements

### Minimum Hardware Requirements

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| CPU | 1 vCPU | 2+ vCPU |
| RAM | 1 GB | 4+ GB |
| Disk | 10 GB | 20+ GB |
| Network | Public IP, ports 80/443 | Static IP |

The Gate software is very efficient — a single node handles up to 100 req/s for all operation types in parallel based on performance tests.

### Software Requirements

| Software | Version | Note |
|----------|---------|------|
| Docker | 24+ | With Docker Compose v2 |
| SSH | — | Server access |
| Domain name | — | For HTTPS certificate |

Additionally for development environment:
- Java 25+
- Node.js 24+
- IntelliJ IDEA (recommended)

---

## Installation with Docker Compose

> **NB:** Docker Compose is suitable for **development environments and PoC testbed**. For production deployment, we recommend using version-tagged Docker images from a container registry (see "Recommended Production Deployment" below).

### 1. Server Preparation

```sh
# Docker installation (Ubuntu/Debian)
curl -fsSL https://get.docker.com | sh

# Directory creation
mkdir -p ~/efti-gate-poc
cd ~/efti-gate-poc
```

### 2. Certificate Generation

An RSA certificate is needed for eDelivery AS4 communication. The certificate identifies the gate to other gates.

```sh
# generate-certificates.sh generates:
# - certs/own.key  — private key
# - certs/own.crt  — certificate (PEM)
# - certs/own.p12  — PKCS12 keystore (password: changeit)
./generate-certificates.sh gate
```

The certificate CN (Common Name) must match the gate's `GATE_ID` value.

In production environments, trusted certificates (not self-signed) must be used.

### 3. Configuration

Create `.env` file:

```env
ENV=prod

# Gate identifier (unique in the eFTI network)
GATE_ID=eu-ee31
COUNTRY=EE

# Database connection
DB_URL=jdbc:postgresql://db/efti
DB_USER=efti
DB_PASS=<strong_password>
DB_APP_PASS=<strong_password>
DB_POOL_SIZE=180
```

### 4. Docker Compose Files

Two compose files are used for production:

- `compose.yml` — base configuration (gate, demo-platform, db)
- `compose.server.yml` — server additions (Caddy reverse proxy, restart policy, volumes)

```sh
# Start
GATE_ID=eu-ee31 docker compose -f compose.yml -f compose.server.yml up -d --wait
```

### 5. Caddy Reverse Proxy

`compose.server.yml` configures Caddy reverse proxy automatically via Docker labels:

- Automatic HTTPS (Let's Encrypt)
- Gate: `https://<GATE_ID>.eftisandbox.eu`
- Demo platform: `https://demo-platform.<GATE_ID>.eftisandbox.eu`

DNS A record must point to the server's IP.

### 6. Verification

```sh
# Health check
curl https://<GATE_ID>.eftisandbox.eu/health

# Admin UI
# In browser: https://<GATE_ID>.eftisandbox.eu/

# OpenAPI
# https://<GATE_ID>.eftisandbox.eu/api/openapi
# https://<GATE_ID>.eftisandbox.eu/v1/openapi
```

### 7. Deploy Update (PoC / Development)

Current deploy script (PoC/development use only):

```sh
# deploy.sh automatically:
# 1. Builds and tests (gradlew test jar, npm test + build)
# 2. Builds Docker images
# 3. Sends images to server (docker save | ssh | docker load)
# 4. Saves existing logs
# 5. Restarts containers
./deploy.sh eu-ee31
```

---

## Recommended Production Deployment

Docker Compose + `deploy.sh` is suitable for development environments, but for **production and testbed deployments** it is recommended to use version-tagged Docker images from a container registry.

### Why Not Docker Compose in Production

| Problem | Description |
|---------|-------------|
| **Version not trackable** | `docker save \| ssh \| docker load` leaves no trace of which version is on the server |
| **No rollback** | Restoring the previous version requires a new deploy |
| **No zero-downtime** | `docker compose up` stops the old container before starting the new one |
| **Image not shareable** | Other parties (testbed partners) cannot access the image |
| **No reproducibility** | Build happens on developer's machine, not in CI pipeline |

### Recommended Deploy Flow

```
Git push → CI (GitHub Actions) → test → build → tag → push registry → deploy
```

**1. Image Tagging:**

Every image must be tagged with a unique identifier:
- **Git commit hash** — `ghcr.io/kemit-ee/efti-gate-poc:a1b2c3d`
- **Semver tag** — `ghcr.io/kemit-ee/efti-gate-poc:1.2.3`
- **Date** — `ghcr.io/kemit-ee/efti-gate-poc:2026-03-10`

Using the `latest` tag in production is **prohibited** — it provides no information about which version is actually running.

**2. Container Registry:**

| Registry | Note |
|----------|------|
| GitHub Container Registry (ghcr.io) | Free for public projects, integrated with GitHub Actions |
| AWS ECR | If infrastructure is on AWS |
| Docker Hub | Universal, but paid for private images |

**3. Deploy to Server:**

```sh
# Production (image from registry, specific version)
docker pull ghcr.io/kemit-ee/efti-gate-poc:1.2.3
docker stop efti-gate && docker rm efti-gate
docker run -d --name efti-gate \
  --restart unless-stopped \
  --env-file /etc/efti-gate/.env \
  -v /etc/efti-gate/certs:/app/certs:ro \
  -p 8080:8080 \
  ghcr.io/kemit-ee/efti-gate-poc:1.2.3
```

Or in Kubernetes (preferred):
```sh
helm upgrade efti-gate charts/efti-gate \
  --set image.tag=1.2.3 \
  -f values-prod.yaml
```

**4. Rollback:**

```sh
# Docker
docker run ... ghcr.io/kemit-ee/efti-gate-poc:1.1.0  # previous version

# Kubernetes
helm rollback efti-gate 1
```

---

## Installation on Kubernetes

### Prerequisites

- Kubernetes cluster (1.24+)
- Helm 3+
- Ingress Controller (nginx-ingress, Traefik, or AWS ALB)
- PostgreSQL database (RDS, CloudNativePG operator, etc.)
- Container Registry (ghcr.io, ECR, etc.)

### 1. Image Build and Push

```sh
# Image build
docker build -f gate/Dockerfile -t <registry>/efti-gate-poc:latest .

# Push to registry
docker push <registry>/efti-gate-poc:latest
```

### 2. Kubernetes Secrets

```sh
# Database passwords
kubectl create secret generic efti-gate-rds \
  --from-literal=password=<DB_PASS> \
  --from-literal=appPassword=<DB_APP_PASS>

# eDelivery certificates
kubectl create secret generic efti-gate-certs \
  --from-file=own.crt=gate/certs/own.crt \
  --from-file=own.key=gate/certs/own.key
```

### 3. Helm Values

Create `values-prod.yaml`:

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

### 4. Installation

```sh
helm install efti-gate charts/efti-gate -f values-prod.yaml
```

---

## Testbed Setup

A testbed is a multi-gate environment where gate-to-gate communication can be tested (identifier broadcast, remote dataset query, follow-up messages).

### Local Testbed (for Development)

`multiple-gates.sh` starts multiple gates in Docker on a shared network:

```sh
# Start (3 gates: estlandia, latveria, lithonia)
./multiple-gates.sh start

# Stop
./multiple-gates.sh stop
```

The script:
1. Builds Docker images
2. Creates a shared Docker network (`efti_gate_test`)
3. Starts each gate as a separate Docker Compose project
4. Registers gates with each other (`add-gate.sh`)

Ports:
- Gate 1: `http://localhost:8081`
- Gate 2: `http://localhost:8082`
- Gate 3: `http://localhost:8083`

### Server-Based Testbed

A testbed can also be set up on a separate server (e.g. VPS), running multiple gate instances. This is necessary for testing with realistic network latency.

#### Option A: Multiple VPS Instances

A separate VPS for each gate. This simulates the real situation where gates are located in different countries.

```
VPS 1 (Hetzner, Germany):    eu-ee31.eftisandbox.eu  — Estonian gate
VPS 2 (Contabo, France):     eu-test1.eftisandbox.eu — Test gate 1
VPS 3 (other provider):      eu-test2.eftisandbox.eu — Test gate 2
```

Each VPS is set up as described above (Docker Compose + Caddy).

#### Option B: One Server, Multiple Instances

Multiple Docker Compose projects on a single server:

```sh
# Gate 1
cd /opt/efti-gate-1
GATE_ID=eu-ee31 docker compose -f compose.yml -f compose.server.yml up -d

# Gate 2
cd /opt/efti-gate-2
GATE_ID=eu-test1 docker compose -f compose.yml -f compose.server.yml up -d
```

NB: Each instance needs a separate `.env` (different `GATE_ID`, `DB_PASS`, certificates) and a separate database.

### Connecting Gates to Each Other

After starting the gates, they need to be registered with each other. This can be done via Admin UI or API:

```sh
# Register Gate 2 in Gate 1
curl -X POST https://eu-ee31.eftisandbox.eu/api/gates \
  -H "Authorization: Basic <admin_credentials>" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "eu-test1",
    "countryCode": "XX",
    "eDeliveryUrl": "https://eu-test1.eftisandbox.eu/services/msh",
    "eDeliveryCert": "<PEM certificate>"
  }'
```

Registration requires:
- **id** — the other gate's unique identifier
- **countryCode** — country code
- **eDeliveryUrl** — eDelivery MSH endpoint URL
- **eDeliveryCert** — the other gate's eDelivery certificate (PEM format)

---

## Connecting Other Countries' eFTI Gates

### Requirements for Other Gates

For another country's eFTI gate to work in our testbed, it must meet the following requirements:

#### Packaging and Operation Requirements

If another country's gate is deployed in **our managed testbed** (i.e. on our server), the following requirements apply:

| # | Requirement | Description |
|---|-------------|-------------|
| 1 | **Docker image** | Gate software must be packaged as a Docker image. This ensures reproducibility, isolation, and easy deployment |
| 2 | **Container registry** | Image must be available from a container registry (Docker Hub, ghcr.io, ECR, etc.). Sending as a `docker save` file is **not acceptable** in production |
| 3 | **Version tag** | Image must be tagged with a specific version (semver, commit hash, or date). `latest` tag is not sufficient — it must be possible to identify the exact version |
| 4 | **Health check endpoint** | HTTP(S) endpoint (e.g. `/health`) that returns 200 OK when the gate is ready to accept traffic. Required for automated monitoring |
| 5 | **Configuration via env variables** | Gate must be configurable via environment variables (port, database, certificates, etc.). Hardcoded configuration inside the image is not suitable |
| 6 | **Non-root user** | Container must run as a non-root user (security) |
| 7 | **Separate database** | If the gate requires a database, it must be configurable as an external connection (not built into the image). PostgreSQL must be supported |
| 8 | **Documentation** | Must include a README describing: all environment variables, required volumes, ports, database requirements, and startup instructions |

If another country's gate runs on **their own server** (not in our testbed), packaging requirements do not apply — only the protocol requirements below are sufficient.

#### Protocol Requirements (for All Gates)

| # | Requirement | Description |
|---|-------------|-------------|
| 1 | **eDelivery AS4 endpoint** | HTTPS endpoint conforming to AS4 protocol (`/services/msh` or similar). Must support MIME multipart messages |
| 2 | **eDelivery certificate** | RSA X.509 certificate (PEM format). Used for message encryption and sender identification |
| 3 | **TLS certificate** | Valid HTTPS certificate on the endpoint (Let's Encrypt or other trusted CA) |
| 4 | **Public HTTPS endpoint** | Endpoint must be accessible from the public internet (port 443) |
| 5 | **eFTI XML schemas** | Message format must conform to eFTI XML schemas (`xsd/` directory) |
| 6 | **Unique Party ID** | Unique identifier used in eDelivery messages |

#### Supported Communication Protocols

| Protocol | Description | Mandatory |
|----------|-------------|-----------|
| **eDelivery AS4** | Standard eFTI gate-to-gate communication. SOAP/MIME, encrypted (AES-GCM + RSA-OAEP) | Yes |
| **Fast Adapter (REST)** | Alternative fast REST-based communication between eFTI Gate PoC gates. `X-API-Key` authentication | No (only between PoC gates) |

#### Supported Operations

The other country's gate must support at least the following operations:

| # | Operation | XML Root Tag | Description |
|---|-----------|--------------|-------------|
| 1 | **Identifier query** | `identifierQuery` → `identifierResponse` | Identifier search. Gate must respond with its own identifiers |
| 2 | **Dataset query (UIL)** | `uilQuery` → `uilResponse` | Dataset retrieval by UIL. Gate must forward the query to the platform |
| 3 | **Follow-up** | `postFollowUpRequest` | Follow-up message forwarding to platform |
| 4 | **Ping** | ebXML test action | Gate availability check |

#### eDelivery Message Format

Messages are exchanged in AS4 format:
- **Transport:** HTTPS POST, `multipart/related` (SOAP envelope + encrypted payload)
- **Encryption:** AES-128-GCM (payload) + RSA-OAEP SHA-256 (symmetric key)
- **Compression:** GZIP
- **Identification:** WS-Security KeyIdentifier (SKI)

#### Steps for Registering Another Gate

1. **Certificate exchange** — both parties exchange eDelivery certificates (PEM). TLS certificates do not need to be exchanged if they are issued by a public CA
2. **Gate registration** — via Admin UI or API (see above)
3. **Ping test** — verify that the gate responds (Admin UI shows gate status ONLINE/OFFLINE)
4. **Identifier query test** — test identifier search
5. **Dataset query test** — test dataset retrieval

### Known Compatibility Issues

| Problem | Description | Solution |
|---------|-------------|----------|
| **Different access points** | Some countries use Domibus, Harmony, CData Arc, etc. Format must conform to AS4 standard | Test with the specific access point |
| **Certificate format** | Some gates have certificates in DER format, not PEM | Convert: `openssl x509 -inform DER -in cert.der -outform PEM -out cert.pem` |
| **Encryption methods** | Non-standard encryption is logged as a warning, but message decryption is attempted | Check logs for warnings |
| **Timeout** | Another country's gate may respond slowly | eDelivery timeout is configurable: `EDELIVERY_TIMEOUT_SECONDS` (default 60s) |

---

## Configuration

### Environment Variables

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `ENV` | Environment (`dev`, `demo`, `prod`) | `dev` | Yes |
| `GATE_ID` | Gate's unique identifier | `POC` | Yes |
| `COUNTRY` | Country code (ISO 3166-1 alpha-2) | `EE` | Yes |
| `DB_URL` | PostgreSQL JDBC URL | — | Yes |
| `DB_USER` | Database user | `efti` | Yes |
| `DB_PASS` | Database password | — | Yes |
| `DB_APP_PASS` | Limited-privilege `app` user password | — | Yes |
| `DB_POOL_SIZE` | Connection pool size | `180` | No |
| `DB_MIGRATE` | Disable migrations (`no`) | — | No |
| `PORT` | HTTP port | `8080` | No |
| `KEYSTORE_DIR` | Certificates directory | `certs` | No |
| `KEYSTORE_PASSWORD` | PKCS12 keystore password | `changeit` | No |
| `EDELIVERY_TIMEOUT_SECONDS` | eDelivery request timeout | `60` | No |
| `OWN_PARTY_ID` | eDelivery Party ID (automatically = GATE_ID) | — | No |

### Database Schema

Database migrations run automatically at startup (`DBMigrator`). Migration files are located in the `gate/db/` directory.

At startup, a limited-privilege `app` user is also automatically created (`gate/db/app_user.sql`), which the application uses after migrations.

---

## Troubleshooting

### Gate Does Not Start

```sh
# View logs
docker compose logs gate

# Health check
curl http://localhost:8080/health
```

Common causes:
- Database not accessible (`DB_URL` incorrect)
- Certificates missing (`certs/own.p12` does not exist)
- Port already in use

### Gate-to-Gate Communication Not Working

1. Check that both gates are running (`/health`)
2. Check that gates are registered with each other (Admin UI → Gates)
3. Check gate status (ONLINE/OFFLINE) — ping job checks every 5 minutes
4. Check logs for eDelivery errors (`Error handling message`, `Could not ping gate`)
5. Check certificate match — registered certificate must match the other gate's actual certificate

### Certificate Issues

```sh
# View certificate info
openssl x509 -in gate/certs/own.crt -text -noout

# View SKI (Subject Key Identifier) — this is logged at startup
openssl x509 -in gate/certs/own.crt -noout -ext subjectKeyIdentifier
```
