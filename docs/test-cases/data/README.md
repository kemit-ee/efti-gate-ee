# Test data (Liquibase)

Six consignments that the data-dependent cases in [../xroad-provide.md](../xroad-provide.md) and
[../admin-api.md](../admin-api.md) rely on. They are a Liquibase changeset, so an administrator loads
them with the migration tool already used for the schema; nothing else to run.

| datasetId | Main transport means | Mode | Registration | Route | Equipment |
|---|---|---|---|---|---|
| `11111111-0000-4000-8000-000000000001` | `TST-001` | road (3) | EE | EE → LV, dangerous goods `1` | — |
| `11111111-0000-4000-8000-000000000002` | `TST-002` | road (3) | DE | DE → EE | used `CONT-100` |
| `11111111-0000-4000-8000-000000000003` | `IMO9321483` | sea (1) | FI | FI → EE | carried `CONT-200`, `CONT-201` |
| `11111111-0000-4000-8000-000000000004` | `OH-LKA` | air (4) | FI | SE → EE | — |
| `11111111-0000-4000-8000-000000000005` | `TST-001` (second trip, same vehicle) | road (3) | EE | EE → FI | — |
| `11111111-0000-4000-8000-000000000006` | `WAGON-77` | rail (2) | LV | LV → EE | — |

All rows: gate `EU-EE`, platform `mock`, status `ACTIVE`.

## Loading

The changeset is [`DSL/Liquibase/test/test-consignments.sql`](../../../DSL/Liquibase/test/test-consignments.sql),
reached only through its own changelog `test/db.changelog-test.yaml`. `db.changelog-master.yaml` does
**not** include it, so the normal migration never loads it (a Liquibase context would not help here:
without `--contexts` Liquibase runs every changeset whatever its context).

Local stack (Liquibase runs as the `liquibase` compose service):

```bash
docker compose run --rm liquibase \
  --defaultsFile=/liquibase/changelog/liquibase.properties \
  --search-path=/liquibase/changelog \
  --changeLogFile=test/db.changelog-test.yaml \
  update -DAUDIT_SALT=dev-local-audit-salt-change-me-32chars
```

Deployed environment: run the same `update` from the liquibase image with the environment's
`efti-liquibase-secret`. A re-run does nothing: Liquibase records the changeset
`test/test-consignments.sql::test-consignments-1` after the first run. Verified on a fresh PostgreSQL 18
with the liquibase image (4.29.2): the master `update` loads 0 test rows, this one loads 6.

Check:

```sql
SELECT dataset_id, main_transport_id FROM consignments
WHERE dataset_id::text LIKE '11111111-0000-4000-8000-%' ORDER BY dataset_id;   -- 6 rows
```

## Removing

The store is append-only, so there is no `DELETE`. `liquibase rollback-count 1 --changeLogFile=test/db.changelog-test.yaml`
appends an `INACTIVE` row per dataset (its rollback block); the datasets then stop matching
`ACTIVE` queries but stay in the history.

## Changing the data

Edit `CASES` in `docs/test-cases/data/generate.py`, run `python3 docs/test-cases/data/generate.py`,
commit the regenerated SQL, and update the expected answers in the case files. Do not edit an
applied changeset in place (Liquibase checksum): add a second changeset instead.

## Notes

- Road datasets accepted more than 14 days ago are marked `INACTIVE` by the hourly
  `expire-identifiers` job (CronManager). Where CronManager runs, the road cases (`…001`, `…002`,
  `…005`) change after 2026-11-15 (acceptance dates are fixed at 2026-11-01/03). Reload with new dates
  before using them then.
- The stored `xml` column holds a minimal `ParameterIDSetCriteria`, not a full FTI004 message; the
  cases only read the denormalised columns.
- Registry authorities used by the cases (`EE/GOV/70000093/ants` with `EU01,EU02`, `…70000099/testSubsystem`
  with `EU01`) are dev fixtures in `registry/authorities/`; confirm they exist in the target registry.
