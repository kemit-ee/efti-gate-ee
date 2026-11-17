# Troubleshooting

Start with the gate's status and the logs of the service that answered (in `local/`):

```bash
docker compose --env-file gates/g1.env ps -a
docker compose --env-file gates/g1.env logs --tail 50 edelivery
```

## `up` fails or hangs

| Symptom | Cause | Fix |
|---|---|---|
| `required variable COMPOSE_PROJECT_NAME is missing a value` (or `GATE_ID`, `PORT_PREFIX`, …) | `docker compose` was run without `--env-file gates/<gate>.env`, or outside `local/` | run it in `local/` with the gate's env file |
| `Bind for 127.0.0.1:27086 failed: port is already allocated` | another process uses the port, often another gate with the same `PORT_PREFIX` | find it with `ss -ltnp \| grep 27086`; stop it or give the gate another prefix |
| `liquibase-seed` is `Exited (1)` and ReSql never starts | the seed failed, e.g. a changed changeset that was already applied (checksum) | `… logs liquibase-seed`; then `… down -v` and `… up -d --wait` for that gate |
| `registry-build` fails with `no local/network/<GATE_ID>.yml` | the gate has no registry entry yet | `… run --rm keygen` ([certificates](../getting-started/certificates.md)) |
| `registry-build` fails in `registry-to-json.py` | an entry is invalid: unknown key, id ≠ file name, expired or unparseable certificate | `… logs registry-build` names the file; a hand-edited file in `network/` is the usual culprit |
| edelivery restarts in a loop with no `partyId: …` log line | `own.p12` missing in `GATE_CERTS_DIR`, wrong `KEYSTORE_PASSWORD`, or alias ≠ gate id | check the folder; recreate the keystore with `keygen` |
| edelivery logs a permission error on `/app/certs` | the key files are not readable for edelivery's user | `chmod 644` the files in `GATE_CERTS_DIR` (`keygen` does this) |
| containers are killed (`Exited (137)`) | out of memory | free about 4 GB, or start one gate at a time |
| `WARN a network with name efti-g2g exists but was not created for project …` | the second and later gates join the network the first one created | harmless; this is how the gates share it |

## AS4 between the gates fails

| Symptom | Cause | Fix |
|---|---|---|
| send API `500 … EBMS:0004 - XML_PARSE_FAIL`; receiver log `reason=XML_PARSE_FAIL` | the sender encrypted for an **old** certificate of the receiver | `up -d --wait` on every gate, so each registry has the current `network/` |
| send API `500 … EBMS:0101 - SIG_CRYPTO_FAIL`; receiver log `reason=SIG_CRYPTO_FAIL` | the receiver checks the signature against an **old** certificate of the sender | same: `up -d --wait` on every gate |
| a gate still uses its old key after `keygen` | edelivery reads the keystore at start; a changed mounted file is not noticed | `… up -d --wait --force-recreate edelivery` on that gate |
| send API `400 Unknown party: EU-XX` | the party id is not in this gate's registry | check `curl 127.0.0.1:27080/gates.json`; add the gate to `network/` and `up` |
| send API `401` | missing or wrong `X-Internal-Service-Token` | use the token from [Ports and credentials](./ports-and-credentials.md) |
| connection refused, or `All 5 attempts failed` after about 15 s | the peer's edelivery is down, still starting, or not on `efti-g2g` | `docker network inspect efti-g2g`; `up -d --wait` the other gate |

Find the reason code quickly:

```bash
docker logs --since 10m efti-g1-edelivery 2>&1 | grep -oE 'reason=[A-Z_]+' | sort | uniq -c
```

## Newman and Postman

| Symptom | Cause | Fix |
|---|---|---|
| Newman: "a row from G2" (or G1) fails on a poll | the peer gate was down or still starting during the search | start both gates with `up -d --wait`, then run the tests again |
| Newman: `ENOTFOUND ruuter` or similar | the newman service ran in another gate's project | pass the same `--env-file` as the gate you test |
| Postman: every request `Could not send request` / `ECONNREFUSED` | the gate isn't running, or the wrong environment is selected | `… ps`; select **eFTI G1 (local)** or **eFTI G2 (local)** |
| Postman: `{{ruuter}}` appears in the URL | no environment selected | select a gate's environment |
| a poll returns `x-poll-more: true` again | the peer hasn't answered yet | press Send again; the Runner retries by itself |
| a poll returns 400 | the search request before it didn't run, so `{{search_id}}` is empty | send the "… starts fan-out" request first |
| `ping` never returns | [known issue](./known-issues.md) H1 | use curl for `ping` |
| `status/{datasetId}` without an id returns 502 `Data layer unavailable` | the route needs the path parameter | use the request as shipped |
| a full FTI004 upload returns 403 | the `GateID` in the XML is not the gate you sent it to | upload to the gate the UIL names |
| counts differ from this book | earlier uploads added rows | `… down -v` and `… up -d --wait` for a clean gate |

`…` stands for `docker compose --env-file gates/<gate>.env`.
