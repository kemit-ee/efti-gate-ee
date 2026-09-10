# get_platform_by_api_key — C6 / ADR-009 rewrite

`platforms/.guard.yml` calls `POST /efti/get_platform_by_api_key` on **every
`/platforms/**` request** (consignment ingest, the G2G `consignments-xml` route).

## Before

```sql
SELECT latest.row_id, latest.id, latest.status
FROM (SELECT DISTINCT ON (id) row_id, id, api_key_hash, status::text AS status
      FROM platforms ORDER BY id, created_at DESC) latest
WHERE latest.status != 'DELETED'
  AND latest.api_key_hash = digest(:apiKey, 'sha256');
```

`DISTINCT ON (id)` materialises the latest row for **every** platform id before
any filter, and `digest()` is compared per row — `idx_platforms_api_key_hash` is
unusable. Same anti-pattern the `get_consignments` C6 rewrite fixed (ADR-009).

## After

```sql
SELECT c.row_id, c.id, c.status
FROM platforms c
WHERE c.api_key_hash = digest(:apiKey, 'sha256')
  AND c.status != 'DELETED'
  AND NOT EXISTS (SELECT 1 FROM platforms c2
                  WHERE c2.id = c.id AND c2.created_at > c.created_at);
```

Filter on the indexed `api_key_hash` first, then a self-correlated `NOT EXISTS`
"no newer row for this id". `digest()` evaluated once.

## Measured (50 000 platform ids × 2 rows = 100 000 rows, PG 18)

| | plan | buffers | exec time |
|---|---|---|---|
| before | Subquery Scan → Unique → Index Scan (whole `idx_platforms_id_latest`) | 100 804 | **92.2 ms** |
| after  | Nested Loop Anti Join + Index Scan `idx_platforms_api_key_hash` + Index Only Scan `idx_platforms_id_latest` | 7 | **0.09 ms** |

~1000×. No new index (both `idx_platforms_api_key_hash` and
`idx_platforms_id_latest` already exist).

## Semantic equivalence

Verified against: active key, rotated key (old rejected / new accepted),
soft-deleted platform, same key hash on two active platforms (`guard_multi`
path), the dev-seed `mock-secret-key`, unknown key — old and new queries return
identical rows in every case. Requiring the matching row to be both non-deleted
**and** the newest for its id is equivalent to taking the newest row and
checking it: a rotated key or a later `DELETE` row is a newer sibling either way.
