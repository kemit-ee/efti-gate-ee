# Test data (Liquibase)

Fourteen consignments (six for the documented cases, eight more for manual plate lookups) that the data-dependent cases in [../xroad-provide.md](../xroad-provide.md) and
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

Second changeset `test-consignments-2`: plates for hand-run lookups. The truck is the main transport
means, the trailer is *used equipment* (category `T10`, registration `EE`), so a lookup by either plate
finds the consignment. Plates are upper case. Containers are carried equipment (`BPR`, valid ISO 6346 ids).

| datasetId | Truck | Trailer(s) | Route | Dangerous goods | Carried containers |
|---|---|---|---|---|---|
| `…0007` | `888UUU` | `735DF` (O2) | EE → LV | `1` explosives | — |
| `…0008` | `031AVH` | `300DK` (O2) | EE → FI | — | `MSKU1234565`, `TGHU7654320` |
| `…0009` | `963APY` | `060TP` (O4) | DE → EE | `3` flammable liquids | — |
| `…0010` | `244AEH` | `337YLN` (light) | EE → LT | — | — |
| `…0011` | `587AXF` | `055YHY` (O4) | EE → PL | — | `MAEU4567896`, `CMAU2345670`, `HLCU9876543` |
| `…0012` | `587AXF` (same truck, other trip) | `611YHZ` (O4 tipper) | EE → EE | — | — |
| `…0013` | `031AVH` (second trip) | `793YKT` (O3) | LV → EE | `8` corrosives | — |
| `…0014` | `557SCG` (bus) | `442DC` (light) | EE → LV | — | — |

Road rows are accepted 2026-11-05..09, so the expiry job leaves them until about 2026-11-19.

All rows: gate `EU-EE`, platform `mock`, status `ACTIVE`.

## Finding each dataset

`UIL` of every row is `{"gateId":"EU-EE","platformId":"mock","datasetId":"<id above>"}`. Calls are the
X-Road ones from [../xroad-provide.md](../xroad-provide.md) (`$BASE`, `X-Road-Client` of a holder of
`EU02` such as `EE/GOV/70000093/ants`, a fresh `X-Road-Id`).

| datasetId | `POST /provide/v1/transport-means` body | `POST /provide/v1/search` body | Expected |
|---|---|---|---|
| `…0001` | `{"identifier":"TST-001","scope":"local"}` | `{"dangerousGoodsCode":{"operator":"EQ","code":"1"}}` | `TST-001` found 2 (`…0005`, `…0001`) / search 1 row |
| `…0002` | `{"identifier":"CONT-100","scope":"local"}` (used equipment) | `{"acceptanceCountry":{"operator":"NE","country":"EE"},"transportMode":{"operator":"EQ","mode":"3"}}` | found 1 / 1 row `TST-002` |
| `…0003` | `{"identifier":"IMO9321483","scope":"existence"}` or `CONT-200` / `CONT-201` (carried) | `{"transportMode":{"operator":"EQ","mode":"1"}}` | registered `true` / sea row |
| `…0004` | `{"identifier":"OH-LKA","scope":"local"}` | `{"transportMode":{"operator":"EQ","mode":"4"}}` | found 1 / 1 row |
| `…0005` | `{"identifier":"TST-001","scope":"local"}` (second trip) | `{"mainTransportId":{"operator":"EQ","id":"TST-001"}}` | newest first: `…0005`, `…0001` |
| `…0006` | `{"identifier":"WAGON-77","scope":"local"}` | `{"transportMode":{"operator":"EQ","mode":"2"}}` | found 1 / rail row |

Fetch a dataset itself with `POST /provide/v1/dataset`, body
`{"uil":{"gateId":"EU-EE","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}`;
the content comes from the `mock` platform (the same for any id), not from the stored row.
A peer gate asks the same through `POST /services/msh` ([../as4-msh.md](../as4-msh.md)).

What to ask for the plates of `…0007`–`…0014` and the expected answers are cases 7.1–7.9 in
[../xroad-provide.md](../xroad-provide.md).

Rows for sea, air and rail were checked through the admin API / SQL only; the cases in
`xroad-provide.md` (4.5–4.9, 6.2–6.6) are the observed ones, the others are derived from the same
columns (**derived**).

## Loading

Automatic. The SQL is [`DSL/Liquibase/test/test-consignments.sql`](../../../DSL/Liquibase/test/test-consignments.sql);
`db.changelog-master.yaml` includes it explicitly at the end, so every normal migration (`docker compose up`,
the `liquibase` image in a deployed environment) loads both changesets once. There is nothing to run.
This also means **every environment whose migration runs gets these rows**, including prelive and any
production-like database.

It is not under `changelog/` on purpose: Liquibase 4.29.2's `includeAll` rejected this formatted-SQL
file in CI ("requires a valid Liquibase Pro key"), while an explicit `include` works. Liquibase records
`test/test-consignments.sql::test-consignments-1` and `-2` in `databasechangelog`, the same identity the
changesets had when they were loaded by hand from the former opt-in changelog, so those databases do not
load them twice.

Check:

```sql
SELECT dataset_id, main_transport_id FROM consignments
WHERE dataset_id::text LIKE '11111111-0000-4000-8000-%' ORDER BY dataset_id;   -- 14 rows
```

## Removing

The store is append-only, so there is no `DELETE`. `liquibase rollback-count 2` appends an `INACTIVE`
row per dataset (the rollback blocks); the datasets then stop matching `ACTIVE` queries but stay in the
history.

## Changing the data

Edit `CASES` in `docs/test-cases/data/generate.py`, run `python3 docs/test-cases/data/generate.py`,
commit the regenerated SQL, and update the expected answers in the case files. Do not edit an
applied changeset in place (Liquibase checksum): add a further changeset (a new `CASES` dict in the generator).

## Notes

- Road datasets accepted more than 14 days ago are marked `INACTIVE` by the hourly
  `expire-identifiers` job (CronManager). Where CronManager runs, the road cases change once their acceptance date is 14 days old:
  `…001`, `…002`, `…005` after 2026-11-15, `…007`–`…014` from 2026-11-19 to 2026-11-23. Add a new
  changeset with new dates before using them then.
- The stored `xml` column holds a minimal `ParameterIDSetCriteria`, not a full FTI004 message; the
  cases only read the denormalised columns.
- Registry authorities used by the cases (`EE/GOV/70000093/ants` with `EU01,EU02`, `…70000099/testSubsystem`
  with `EU01`) are dev fixtures in `registry/authorities/`; confirm they exist in the target registry.
