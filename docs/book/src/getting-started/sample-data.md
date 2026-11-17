# Load the sample data

**You don't need to do anything here on a first run.** `up` already loaded the data. This chapter
explains where it comes from and how to check, reload or change it.

## How it is loaded

Each gate has its own **Liquibase run for sample data**: the Compose service `liquibase-seed`, in
the profile `seed`.

- **Whether it runs:** the gate env file turns it on with `COMPOSE_PROFILES=seed`. A gate whose env
  file leaves `COMPOSE_PROFILES` empty starts with an empty database.
- **When it runs:** after the schema migration (`liquibase`). ReSql waits for it to finish, so the
  gate never serves requests on a half-seeded database.
- **What it applies:** the gate's own test changelog, `GATE_SEED_CHANGELOG` in
  `local/gates/<gate>.env`.

| Gate | Changelog | Datasets |
|---|---|---|
| G1 | `DSL/Liquibase/test/db.changelog-local-g1.yaml` → `test-consignments-g1.sql` | 6 (`11111111-0000-4000-8000-00000000000{1..6}`) |
| G2 | `DSL/Liquibase/test/db.changelog-local-g2.yaml` → `test-consignments-g2.sql` | 4 (`22222222-0000-4000-8000-00000000000{1..4}`) |

These changelogs are **not** part of `db.changelog-master.yaml`, so a normal migration, in any
environment, never loads test data. Liquibase records each applied changeset, so running it again
applies nothing.

Some identifiers exist at only one gate. That is what proves a search really crossed to the other
gate. The full table is in [Sample data](../reference/sample-data.md).

| Identifier | Only at |
|---|---|
| `G2-AB123` (road), `IMO9876543` (vessel) | G2 |
| `TST-002` (road), `IMO9321483` (vessel) | G1 |
| `TST-001` | **both**, on purpose: G1 has two trips, G2 one |

## Check it

```bash
for g in g1 g2; do
  docker exec efti-$g-database psql -U efti -d efti -Atc \
    "SELECT gate_id, count(DISTINCT dataset_id) FROM consignments
     WHERE dataset_id::text LIKE '11111111-%' OR dataset_id::text LIKE '22222222-%' GROUP BY gate_id"
done
```

```text
G1|6
G2|4
```

The changeset Liquibase recorded:

```bash
docker exec efti-g2-database psql -U efti -d efti -Atc \
  "SELECT id, filename FROM databasechangelog WHERE id LIKE 'test-consignments%'"
# test-consignments-g2-1|test/test-consignments-g2.sql
```

Or ask through the API, the way an authority would (G1's X-Road provider API, a local hit):

```bash
curl -s -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":"TST-001","scope":"existence"}'
# {"countryCode":"","identifier":"TST-001","registered":true,"scope":"existence"}
```

## Reload or start clean

The Postman upload requests (folder 5) add new datasets every time you run them. The store is
append-only, so nothing ever gets deleted. To go back to *only* the sample data, delete the gate's
volumes and start it again (in `local/`):

```bash
docker compose --env-file gates/g1.env down -v      # deletes G1's data volumes
docker compose --env-file gates/g1.env up -d --wait  # migrates and seeds again
```

To run only the seed pipeline again, for a gate whose seed run failed:

```bash
docker compose --env-file gates/g1.env run --rm liquibase-seed
```

On a database that already has the changeset this applies nothing (`Run: 0, Previously run: 1`).

## Change the data

Both SQL files are written by the repository's test-data generator,
`docs/test-cases/data/generate.py` (`CASES` for G1, `CASES_G2` for G2). Change the data there
and regenerate, or edit the SQL directly if you keep it in step yourself.

Liquibase refuses a changed changeset that was already applied (checksum). Either delete the gate's
volumes (`down -v`, then `up`), or add a new changeset instead of editing the old one.

**Sample data for a new gate:** write a changelog under `DSL/Liquibase/test/` (copy
`db.changelog-local-g2.yaml` and its SQL, with the new gate id in `gate_id` and new dataset ids), and
point `GATE_SEED_CHANGELOG` at it.

Next: [Create and exchange gate certificates](./certificates.md).
