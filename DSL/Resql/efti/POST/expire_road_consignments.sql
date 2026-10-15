/*
description: cabotage-expiry sweep (Reg 2024/1942 Art 11(4)). Picks up to 1000 current road
  consignments (transport_mode 3, latest row ACTIVE) whose carrier acceptance date is more than
  14 days ago and appends an INACTIVE copy of each as its new latest row (append-only, no UPDATE).
  With dryRun 'true' it only counts the candidates. Idempotent: an expired dataset is no longer a
  candidate, and a concurrent run can at most append a second identical INACTIVE row.
params:
  dryRun: { type: string, default: "false" }
*/
WITH candidates AS (
  SELECT c.*
  FROM consignments c
  WHERE c.transport_mode = '3'
    AND c.status = 'ACTIVE'
    AND c.acceptance_date < now() - interval '14 days'
    AND NOT EXISTS (
      SELECT 1 FROM consignments n
      WHERE n.platform_id = c.platform_id
        AND n.dataset_id  = c.dataset_id
        AND (n.created_at, n.revision) > (c.created_at, c.revision)
    )
  ORDER BY c.acceptance_date, c.row_id
  LIMIT 1000
),
expired AS (
  INSERT INTO consignments (
    dataset_id,
    platform_id,
    gate_id,
    xml,
    status,
    transport_mode,
    acceptance_date,
    acceptance_country,
    delivery_date,
    delivery_country,
    dangerous_goods,
    main_transport_id,
    main_transport_type,
    transport_reg_country,
    loading_date,
    loading_country,
    unloading_date,
    unloading_country,
    used_equipment_ids,
    used_equipment_categories,
    used_equipment_countries,
    used_equipment_seq,
    carried_equipment_ids,
    carried_equipment_categories,
    carried_equipment_seq
  )
  SELECT
    dataset_id,
    platform_id,
    gate_id,
    xml,
    'INACTIVE'::consignment_status,
    transport_mode,
    acceptance_date,
    acceptance_country,
    delivery_date,
    delivery_country,
    dangerous_goods,
    main_transport_id,
    main_transport_type,
    transport_reg_country,
    loading_date,
    loading_country,
    unloading_date,
    unloading_country,
    used_equipment_ids,
    used_equipment_categories,
    used_equipment_countries,
    used_equipment_seq,
    carried_equipment_ids,
    carried_equipment_categories,
    carried_equipment_seq
  FROM candidates
  WHERE COALESCE(:dryRun, 'false') <> 'true'
  RETURNING row_id
)
SELECT
  (SELECT count(*) FROM candidates)::int AS candidate_count,
  (SELECT count(*) FROM expired)::int    AS expired_count;
