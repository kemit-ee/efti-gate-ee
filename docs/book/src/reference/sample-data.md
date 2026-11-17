# Sample data

All datasets use platform `mock` and status `ACTIVE`. How they are loaded is described in
[Load the sample data](../getting-started/sample-data.md); the generator is
`docs/test-cases/data/generate.py`.

## G1: `test-consignments-g1.sql`

| datasetId | Main transport means | Mode | Registration | Route | Equipment |
|---|---|---|---|---|---|
| `11111111-0000-4000-8000-000000000001` | `TST-001` | road (3) | EE | EE → LV, dangerous goods `1` | — |
| `11111111-0000-4000-8000-000000000002` | `TST-002` | road (3) | DE | DE → EE | used `CONT-100` |
| `11111111-0000-4000-8000-000000000003` | `IMO9321483` | sea (1) | FI | FI → EE | carried `CONT-200`, `CONT-201` |
| `11111111-0000-4000-8000-000000000004` | `OH-LKA` | air (4) | FI | SE → EE | — |
| `11111111-0000-4000-8000-000000000005` | `TST-001` (second trip) | road (3) | EE | EE → FI | — |
| `11111111-0000-4000-8000-000000000006` | `WAGON-77` | rail (2) | LV | LV → EE | — |

## G2: `test-consignments-g2.sql`

| datasetId | Main transport means | Mode | Registration | Route | Equipment |
|---|---|---|---|---|---|
| `22222222-0000-4000-8000-000000000001` | `G2-AB123` | road (3) | LV | LV → EE | — |
| `22222222-0000-4000-8000-000000000002` | `TST-001` (third trip) | road (3) | EE | LV → LT, dangerous goods `1` | — |
| `22222222-0000-4000-8000-000000000003` | `G2-XY987` | road (3) | LT | LT → LV | used `CONT-300` |
| `22222222-0000-4000-8000-000000000004` | `IMO9876543` | sea (1) | LV | LV → EE | carried `CONT-400`, `CONT-401` |

## Which identifier to use for what

| To test | Use | Asked at | Expect |
|---|---|---|---|
| a local hit (no fan-out) | `TST-001` | either gate | that gate's rows only, `x-poll-more: false` |
| search across gates, G1 → G2 | `G2-AB123` | G1 | `[]` + `x-search-id`, then the poll returns the G2 row |
| search across gates, G2 → G1 | `TST-002` | G2 | the poll returns the G1 row |
| transport means, `allgates` | `IMO9876543` at G1, `IMO9321483` at G2 | | the poll returns the other gate's vessel |
| a dataset from the other gate | G2 `…0001` from G1, G1 `…0001` from G2 | | `200` with `consignment` and `xml` |

Dataset content always comes from the **mock platform** (`MOCK-PLATE-1`), not from the identifier row.
Rows that arrive from the other gate in a search carry only `uil` and `mainTransportId`.
