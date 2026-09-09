/*
description: copy superseded consignments rows into the separate archive DB (over postgres_fdw)
params:
  olderThanDays: { type: number, default: 2 }
  batchLimit: { type: number, default: 1000 }
*/
-- Candidates: rows that have a NEWER sibling row for the same (platform_id, dataset_id) AND whose
-- newer sibling is itself older than the retention window — so the current row of every dataset,
-- including a DELETED tombstone, is never a candidate. `NOT EXISTS (... archive ...)` makes the
-- copy idempotent without ON CONFLICT (not supported on a foreign table). The advisory xact lock
-- serialises concurrent runs so the NOT EXISTS check can't race into a remote PK violation.
WITH _lock AS MATERIALIZED (
  SELECT pg_advisory_xact_lock(hashtext('archive_consignments')) AS locked
),
candidates AS (
  SELECT c.row_id
  FROM consignments c, _lock
  WHERE EXISTS (
    SELECT 1 FROM consignments n
    WHERE n.platform_id = c.platform_id
      AND n.dataset_id  = c.dataset_id
      AND n.created_at   > c.created_at
      AND n.created_at   < now() - make_interval(days => COALESCE(:olderThanDays, 2)::int)
  )
  AND NOT EXISTS (
    SELECT 1 FROM archive.consignments a WHERE a.row_id = c.row_id
  )
  ORDER BY c.created_at
  LIMIT COALESCE(:batchLimit, 1000)
),
copied AS (
  INSERT INTO archive.consignments (
    row_id, dataset_id, platform_id, gate_id, xml, status, transport_mode,
    acceptance_date, acceptance_country, delivery_date, delivery_country, dangerous_goods,
    main_transport_id, main_transport_type, transport_reg_country,
    loading_date, loading_country, unloading_date, unloading_country,
    used_equipment_ids, used_equipment_categories, used_equipment_countries, used_equipment_seq,
    carried_equipment_ids, carried_equipment_categories, carried_equipment_seq,
    created_at, archived_at
  )
  SELECT
    c.row_id, c.dataset_id, c.platform_id::text, c.gate_id::text, c.xml, c.status::text, c.transport_mode,
    c.acceptance_date, c.acceptance_country, c.delivery_date, c.delivery_country, c.dangerous_goods,
    c.main_transport_id, c.main_transport_type, c.transport_reg_country,
    c.loading_date, c.loading_country, c.unloading_date, c.unloading_country,
    c.used_equipment_ids, c.used_equipment_categories, c.used_equipment_countries, c.used_equipment_seq,
    c.carried_equipment_ids, c.carried_equipment_categories, c.carried_equipment_seq,
    c.created_at, now()
  FROM consignments c
  JOIN candidates x USING (row_id)
  RETURNING row_id
)
SELECT
  (SELECT count(*)                FROM candidates) AS candidate_count,
  (SELECT count(*)                FROM copied)     AS newly_copied_count,
  (SELECT array_agg(row_id::text) FROM candidates) AS candidate_row_ids;
