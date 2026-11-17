# Verify with Postman

The repository ships a Postman collection for one gate. It covers every endpoint a peer gate, a
competent authority, a platform, an administrator or an operator can call, plus the gate-internal
hops behind them. Every request carries tests, so a green result means the answer was checked, not
just that it arrived.

The collection is **gate-agnostic**: it runs against whichever gate's environment you select, and its
cross-gate requests go to that environment's peer gate over AS4. Running it once with each gate's
environment tests both directions.

**Prerequisite:** both gates are running ([chapter 2](./run-locally.md)). Check with
`curl http://127.0.0.1:27086/efti/health/ready` (and 28086).

## Files

Under `local/postman/`:

| File | Purpose |
|---|---|
| `eFTI-Gate.postman_collection.json` | 118 requests in 10 folders, with tests |
| `G1.postman_environment.json` | G1's base URLs (host ports 27xxx), its peer G2, the seeded test data, dev credentials |
| `G2.postman_environment.json` | The same for G2 (28xxx), peer G1 |
| `eFTI-Gate-known-issues.postman_collection.json` | 4 requests that show known defects; they fail on purpose |

## Import

1. In Postman: **Import**, then drop all four JSON files.
2. In the environment selector (top right), pick **eFTI G1 (local)** or **eFTI G2 (local)**.
3. Open the collection **eFTI Gate**.

The collections and environments have fixed ids, so importing a newer version replaces the old one.

> **Postman on the web** can't reach `127.0.0.1` by itself. Use the desktop app, or the web app
> with the Postman desktop agent.

## The environment

Every value that differs between gates is an environment variable. The G1 environment:

| Variable | G1 | Meaning |
|---|---|---|
| `ruuter`, `xroad`, `edelivery`, `xml_mapper`, `resql`, `registry`, `devmock` | `http://127.0.0.1:27086`, `…:27087/xroad`, `…:27081`, … | Base URLs of the gate's services |
| `own_gate`, `peer_gate` | `G1`, `G2` | This gate and the gate the cross-gate requests go to |
| `own_ds`, `own_tst001_ds` | `11111111-…-0001` | Seeded datasets of this gate (any one, and the one with `TST-001`) |
| `peer_ds`, `peer_tst001_ds` | `22222222-…-0001`, `22222222-…-0002` | The same at the peer gate |
| `peer_only_plate`, `peer_only_vessel` | `G2-AB123`, `IMO9876543` | Identifiers seeded only at the peer, so a hit proves the search crossed gates |
| `internal_token`, `ops_token`, `resql_token`, `platform_key` | dev values | [Ports and credentials](../reference/ports-and-credentials.md) |
| `client_eu01`, `client_tm`, `devmock_client`, `admin_personal_code` | dev fixtures | X-Road clients and the dev admin |

For another gate, copy an environment file and change these values.

## The folders

| Folder | Who calls | What it proves |
|---|---|---|
| **0. Health** | — | Ruuter (and its DB), Ruuter-xroad and edelivery answer |
| **1. CA → Gate** | a competent authority over X-Road | the identity guard, subsets, search, transport means, dataset and follow-up. Local requests are answered by this gate; remote ones go to the peer gate over AS4 |
| **2. G2G → peer gate** | the gate itself | edelivery wraps FTI019 (search), FTI009 (dataset) and FTI025 (follow-up) in AS4, sends them to the peer and returns its answer |
| **3. G2G inbound** | the receiving edelivery | the handlers that answer an inbound AS4 request, and the public AS4 edge `/services/msh` |
| **4. Core authority API** | the X-Road adapter / UI (gate-internal) | the same functions one layer down, to tell an adapter problem from a core problem |
| **5. Platform → Gate** | a freight platform | identifier upload (short and full FTI004), the API key, the UIL ownership check, the eDelivery upload path |
| **6. Administrators** | an administrator (dev-login JWT) | login, `auth/user`, logout; users; read-only registries; consignments, read models, audit. Logs in by itself |
| **7. Operations** | an operator, CronManager | health probes of every service; the seven `/ops/v1` jobs with safe parameters |
| **8. Developer mock** | an authority developer | all routes of the public X-Road developer mock |
| **9. Gate internals** | the gate's own containers | registry documents, ReSql, xml-mapper conversions |

Every request is listed in [The Postman collection](../reference/collection.md).

## Try these first

With the **G1** environment selected:

1. **0. Health → `ruuter health/ready`**: `200 "OK"`.
2. **1. CA → Gate → Identity guard → `echo`**: `200` with the authority `auth-xroad-test` that the
   `X-Road-Client` header resolves to.
3. **1 → Search → `search - local hit TST-001`**: `200` with G1's two TST-001 rows. A local hit
   answers at once and does not ask other gates; that is by design.
4. **1 → Search → `search - peer-only plate starts fan-out`**: `200`, body `[]`, header
   `x-poll-more: true` and `x-search-id: <uuid>`. G1 has no `G2-AB123`, so it has asked G2
   over AS4.
5. **1 → Search → `search - poll peer-only plate`**: `200`, header `x-poll-more: false`, one row with
   `uil.gateId: "G2"`. **This one result proves the whole path:** authority → G1 X-Road adapter →
   EE core → EE edelivery → AS4 → LV edelivery → LV core → LV database, and back.
6. Switch to the **G2** environment and send **2. G2G → `FTI009 dataset`**: the same kind of trip
   the other way, at the XML level. The answer is an `FTI010GetCmdsResponse`.

## How the requests work

- **A fresh id on every request.** The collection's pre-request script puts a new UUID in `{{rid}}`.
  Requests use it as `X-Road-Id` (X-Road) or `X-Request-Id` (gate-internal) and as the message id
  inside the FTI XML. The X-Road API rejects a missing or non-UUID `X-Road-Id` with 400.
- **No Security Server locally.** In production the X-Road Security Server sets `X-Road-Client`.
  Here the request sets it itself:

  | Client | Authority | Subsets |
  |---|---|---|
  | `DEV/GOV/70000099/testSubsystem` | `auth-xroad-test` | `EU01` |
  | `DEV/GOV/70000093/ants` | `auth-xroad-tm` | `EU01`, `EU02`, needed for transport means |

- **Searches across gates take two steps.** This applies to `search` and to `transport-means` with
  `scope: allgates`. On a local miss the gate starts the AS4 fan-out and answers at once with
  `x-poll-more: true` and `x-search-id`. The test script stores the id in a collection variable
  (`search_id`, `tm_search_id`). The **poll** request sends
  `{"poll": true, "searchId": "{{search_id}}"}` with a new `X-Road-Id`.
  - By hand: if the poll still says `x-poll-more: true`, press Send again.
  - In the Collection Runner and Newman: the poll repeats itself (up to 10 times) until it's done.
- **Follow-ups refer to a dataset request.** "follow-up - remote" sends the `X-Road-Id` of the
  "dataset - remote" request before it as its `referenceIds`. Run that one first.
- **The admin requests log in by themselves.** A pre-request script calls `/auth/dev-login` when no
  token is cached, and reuses the token for 50 minutes.
- **The XML bodies come from the schema samples** (`code/xml-mapper/xsd/<FTI>/sample.xml`), with the
  UIL and ids swapped for variables.

## Run the whole collection

1. Click the collection **eFTI Gate**, then **Run**.
2. Click **Run eFTI Gate**.

Expect every test green: 118 requests and 199 tests per gate, in about 10 seconds. Run it with both
environments.

The uploads in folder 5 create new datasets each time (fresh UUIDs), so you can run the collection
as often as you like. To go back to only the sample data, see
[Reload or start clean](./sample-data.md#reload-or-start-clean).

The known-issues collection is separate so that a full run stays green. Run its requests by hand;
each one documents the defect it shows ([Known issues](../reference/known-issues.md)).

## Changing the collection

The JSON files are the source. Edit the collection in Postman, then export it over
`local/postman/eFTI-Gate.postman_collection.json` (Collection v2.1) and commit it. Keep requests
gate-agnostic: use the environment variables above, never a literal gate id, port or dataset id.
Update [The Postman collection](../reference/collection.md) in the same change.

Next: [Run the checks from the command line](./newman.md).
