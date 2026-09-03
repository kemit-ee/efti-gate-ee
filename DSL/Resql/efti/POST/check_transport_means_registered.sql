/*
description: Existence check for a transport-means identifier — does this gate hold any ACTIVE
  consignment for it? Returns exactly one row carrying a single boolean flag, and no consignment
  data. Backs the existence scope on the X-Road transport-means service (the ANTS border-check path).
params:
  transport_means_id: { type: string, required: true }
  country_code:       { type: string }
*/
-- The sub-second, >10 000/hour path. Cheaper than get_consignments_by_transport_means.sql because
-- EXISTS short-circuits on the first qualifying consignment: no 50-row sort, no jsonb_build_object,
-- no column payload on the wire. Returns a boolean and nothing else, so it cannot leak consignment
-- data even by accident.
--
-- IT IS NOT A NAIVE EXISTS OVER RAW ROWS. consignments is append-only, so
--
--     SELECT EXISTS (SELECT 1 FROM consignments WHERE main_transport_id = :id)
--
-- would answer `registered: true` for a consignment that has since been soft-deleted, or whose
-- plate was corrected — the old row is still there forever. At a border that is the worst possible
-- error: a truck reported as having a valid eFTI dataset when it does not. So the EXISTS wraps the
-- SAME latest-row-then-filter query the projection uses, and inherits its correctness:
-- candidates are narrowed by index, each consignment's LATEST row is resolved unfiltered, and only
-- then must that row still carry the identifier and still be ACTIVE.
--
-- Matches all three identifier families (main transport means, used equipment, carried equipment)
-- so that a container-number check cannot answer "not registered" merely because the number is on
-- the equipment rather than the tractor.
SELECT EXISTS (
  SELECT 1
  FROM (
    SELECT DISTINCT ON (dataset_id, platform_id)
      main_transport_id,
      used_equipment_ids,
      carried_equipment_ids,
      transport_reg_country,
      status::text AS status
    FROM consignments
    WHERE (dataset_id, platform_id) IN (
      SELECT dataset_id, platform_id
      FROM consignments
      WHERE main_transport_id = :transport_means_id
         OR :transport_means_id = ANY(used_equipment_ids)
         OR :transport_means_id = ANY(carried_equipment_ids)
    )
    ORDER BY dataset_id, platform_id, created_at DESC
  ) latest
  WHERE (latest.main_transport_id = :transport_means_id
         OR :transport_means_id = ANY(latest.used_equipment_ids)
         OR :transport_means_id = ANY(latest.carried_equipment_ids))
    AND latest.status = 'ACTIVE'
    AND (:country_code IS NULL OR :country_code = '' OR latest.transport_reg_country = :country_code)
  LIMIT 1
) AS registered;
