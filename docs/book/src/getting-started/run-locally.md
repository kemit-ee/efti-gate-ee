# Run two gates locally

The local network is plain Docker Compose. One file, `local/compose.yaml`, describes one gate. Each
gate runs that same file as its own Compose project, with its own env file in `local/gates/`. The two
gates of this book are G1 (`gates/g1.env`) and G2 (`gates/g2.env`).

All commands run in the `local/` folder of the repository:

```bash
cd local
```

## Initial setup

```bash
docker compose --env-file gates/g1.env up -d --wait      # 1. gate G1
docker compose --env-file gates/g2.env up -d --wait      # 2. gate G2
docker compose --env-file gates/g1.env run --rm newman   # 3. test G1 (and AS4 to G2)
docker compose --env-file gates/g2.env run --rm newman   # 4. test G2 (and AS4 to G1)
```

Steps 3 and 4 are part of the setup, not optional: they prove that each gate works and that the two
gates reach each other over AS4. Both must end with 0 failed assertions:

```text
│                requests │               119 │                0 │
│              assertions │               199 │                0 │
```

The test runs come after both gates are up, because half of the tests go to the other gate.
[Chapter 6](./newman.md) explains the test service and how to run it on a schedule.

What `up` does for one gate:

1. **Pulls the published images**: Ruuter, ReSql and TIM from Docker Hub (`turnerrainer/*`),
   Liquibase, PostgreSQL. The gate's DSL, SQL and config files are mounted from the repository, so
   nothing of the gate is baked into them.
2. **Builds three images from source**: edelivery and xml-mapper (Kotlin, `docker/code/Dockerfile`),
   and `registry-build` (`docker/registry-build`, code only; the `registry` service is stock nginx with a
   config file). No image contains registry data, keys or passwords. The first build takes a few minutes; later starts
   reuse the images.
3. **Creates the shared network `efti-g2g`** if no gate has created it yet.
4. **Starts the gate in order**:
   1. PostgreSQL (live and archive).
   2. `liquibase` creates the schema; `liquibase-seed` loads the sample data
      ([chapter 3](./sample-data.md)).
   3. `tim-keygen` creates TIM's JWT signing key on the first start.
   4. `registry-build` (validates and publishes the registry, then exits), ReSql, the registry, TIM,
      xml-mapper, the developer mock.
   5. Ruuter (it writes its `constants.ini` with this gate's `OWN_GATE_ID`), edelivery,
      Ruuter-xroad.

   `--wait` returns when every service is healthy and every one-shot job has finished.

## Check that it's up

```bash
docker compose --env-file gates/g2.env ps -a
```

Each gate has 14 services. `liquibase`, `liquibase-seed` and `tim-keygen` are one-shot jobs and
show **`Exited (0)`**; everything else is `Up (healthy)`:

```text
SERVICE            STATUS                     PORTS
archive-database   Up 2 minutes (healthy)     5432/tcp
database           Up 2 minutes (healthy)     127.0.0.1:28032->5432/tcp
edelivery          Up 2 minutes (healthy)     127.0.0.1:28081->8081/tcp
liquibase          Exited (0) 2 minutes ago
liquibase-seed     Exited (0) 2 minutes ago
registry           Up 2 minutes (healthy)     80/tcp, 127.0.0.1:28080->8080/tcp
registry-build     Exited (0) 2 minutes ago
resql              Up 2 minutes (healthy)     8080/tcp, 127.0.0.1:28090->8090/tcp
ruuter             Up 2 minutes (healthy)     8080/tcp, 127.0.0.1:28086->8086/tcp
ruuter-xroad       Up 2 minutes (healthy)     8080/tcp, 127.0.0.1:28087->8087/tcp
ruuter-xroad-mock  Up 2 minutes (healthy)     8080/tcp, 127.0.0.1:28088->8088/tcp
tim                Up 2 minutes (healthy)     8085/tcp
tim-database       Up 2 minutes (healthy)     5432/tcp
tim-keygen         Exited (0) 2 minutes ago
xml-mapper         Up 2 minutes (healthy)     127.0.0.1:28082->8082/tcp
```

Quick probes (G1 shown; G2 is the same on 28xxx):

```bash
curl http://127.0.0.1:27086/efti/health/ready      # "OK"  - Ruuter, and its database through ReSql
curl http://127.0.0.1:27087/xroad/health/ready     # "OK"  - the X-Road provider API
curl http://127.0.0.1:27081/services/msh           # eDelivery MSH endpoint is up, please send POST requests
curl http://127.0.0.1:27088/developer/health/ready # "OK"  - the X-Road developer mock
```

One admin login proves TIM works (the token is valid for 60 minutes):

```bash
curl -s -X POST http://127.0.0.1:27086/auth/dev-login -H 'Content-Type: application/json' \
  -d '{"personalCode":"60001019906","firstName":"Super","lastName":"Admin"}'
# {"token":"eyJ0eXAiOiJKV1Qi…"}
```

Each gate's registry lists every gate of the network, at its AS4 address on `efti-g2g`:

```bash
curl -s http://127.0.0.1:27080/gates.json
```

```text
[{"id":"G1","countryCode":"XA","eDeliveryUrl":"http://g1-edelivery:8081/services/msh","status":"ONLINE","eDeliveryCert":"-----BEGIN CERTIFICATE-----\n…"},
 {"id":"G2","countryCode":"XB","eDeliveryUrl":"http://g2-edelivery:8081/services/msh","status":"ONLINE","eDeliveryCert":"-----BEGIN CERTIFICATE-----\n…"}]
```

Only the edelivery containers are on the shared network:

```bash
docker network inspect efti-g2g --format '{{range .Containers}}{{.Name}} {{end}}'
# efti-g1-edelivery efti-g2-edelivery
```

## How a gate is put together

| Part | Where | What it is |
|---|---|---|
| The gate | `local/compose.yaml` | 15 services, the same for every gate. Each service gets the container name `<project>-<service>`, e.g. `efti-g2-ruuter`. |
| The gate's parameters | `local/gates/<gate>.env` | Project name, gate id, country, network alias, keystore folder, sample data, host port prefix. |
| The network's gate list | `local/network/<GATE_ID>.yml` | One registry entry per gate (id, AS4 address, public certificate). Every gate's `registry-build` mounts all of them as `gates/`. |
| AS4 keystores | `local/certs/g1/`, `local/certs/g2/` | `own.p12` and its parts ([chapter 4](./certificates.md)). |
| Postman | `local/postman/` | One gate-agnostic collection, one environment per gate ([chapter 5](./postman.md)). |

A gate env file, complete:

```bash
COMPOSE_PROJECT_NAME=efti-g2           # Compose project: containers efti-g2-*, volumes efti-g2_*
COMPOSE_PROFILES=seed                  # load the sample data; empty for a gate without it
GATE_ID=G2
GATE_COUNTRY=XB                        # registry countryCode; XA, XB, … are ISO user-assigned codes, no real country
GATE_SLUG=g2                           # alias on the shared network: g2-edelivery
GATE_CERTS_DIR=./certs/g2              # AS4 keystore folder
GATE_SEED_CHANGELOG=test/db.changelog-local-g2.yaml
PORT_PREFIX=28                         # host ports 28086, 28087, 28088, 28081, 28082, 28090, 28080, 28032, 28089
```

**Gates meet only over AS4.** Every service except edelivery stays on its gate's own default
network, so `ruuter`, `resql` and the other service names resolve inside one gate and never reach the
other. edelivery also joins `efti-g2g`, under the alias `<GATE_SLUG>-edelivery`; the entries in
`local/network/` point there.

**What is not started:** the admin UI (use the API directly), the Turvis mocks (edelivery runs
`TURVIS_INTEGRATION_MODE=legacy`), observability, and CronManager for the gate's own jobs. Without
CronManager nothing turns the road sample data `INACTIVE` after 14 days; run the jobs by hand when you
need them ([Scheduled jobs](../api/operations/jobs.md)). TARA login (`/auth/callback`) is not
available; `/auth/dev-login` replaces it.

## Adding a gate

A third gate, G3, as an example. Everything is a parameter; no file of the gate itself changes.

1. Copy an env file and change every value:

   ```bash
   cp gates/g2.env gates/g3.env
   # COMPOSE_PROJECT_NAME=efti-g3, GATE_ID=G3, GATE_COUNTRY=XC, GATE_SLUG=g3,
   # GATE_CERTS_DIR=./certs/g3, PORT_PREFIX=29, and COMPOSE_PROFILES= (no sample data for G3)
   ```

2. Create its key pair and its registry entry:

   ```bash
   docker compose --env-file gates/g3.env run --rm keygen
   ```

   This writes `certs/g3/own.{key,crt,p12}` and `network/G3.yml`. On Linux with a user id other
   than 1000, add `-e HOST_UID=$(id -u) -e HOST_GID=$(id -g)` so the files belong to you.

3. Re-run `registry-build` on the running gates, so their registries know G3 (no image is rebuilt):

   ```bash
   docker compose --env-file gates/g1.env up -d registry-build
   docker compose --env-file gates/g2.env up -d registry-build
   ```

   edelivery reads the registry every 10 seconds; no restart is needed.

4. Start it:

   ```bash
   docker compose --env-file gates/g3.env up -d --wait
   ```

This was run for this book: G3 started in 30 seconds, every registry listed three gates, AS4 worked
between all three pairs, and an authority search at G3 for `G2-AB123` fanned out to G1 and G2
and returned the G2 row.

To test G3 with Newman, it also needs a Postman environment (`postman/G3.postman_environment.json`,
a copy of another gate's with its own values) and sample data, because the tests search seeded
identifiers ([Verify with Postman](./postman.md)).

## Day-to-day commands

Every command takes the gate's env file. Run them in `local/`.

| Command | Effect |
|---|---|
| `docker compose --env-file gates/g1.env up -d --wait` | Start the gate, or apply changes (a changed mount or env value recreates the service) |
| `… up -d --build` | Rebuild the images built from source (edelivery, xml-mapper, registry-build) and restart |
| `… ps -a` | Container status |
| `… logs -f edelivery` | Follow one service's log |
| `… restart ruuter` | Restart one service, e.g. after a DSL change |
| `… run --rm newman` | Run the Postman collection against the gate ([chapter 6](./newman.md)) |
| `… down` | Stop and remove the containers; **the data volumes stay** |
| `… down -v` | Stop and **delete the gate's data volumes**; the next `up` migrates and seeds again |

The shared network `efti-g2g` stays as long as a gate uses it. When the last gate is down,
`docker network rm efti-g2g` removes it; the next `up` creates it again.

Next: [Load the sample data](./sample-data.md).
