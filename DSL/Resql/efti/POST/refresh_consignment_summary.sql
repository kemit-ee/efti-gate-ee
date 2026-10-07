/*
description: ADR-012 read-model refresh for the authority consignment summary - one new generation holding, per
  registration day, how many current ACTIVE consignments fall into each dimension value. Each dimension is tagged
  with the eFTI subset that entitles a reader to it (EU02 dangerous goods, EU03 loading/unloading country, EU04
  transport mode/type/registration country); the empty subset is the ungated total. Published with the pointer row
  in the same statement; an advisory transaction lock serialises overlapping refreshes. Called only by
  POST /ops/v1/refresh-read-models.
params: {}
*/
WITH serialised AS (
  SELECT pg_advisory_xact_lock(hashtextextended('read_model:consignment_summary', 0))
),
new_generation AS (
  SELECT nextval('read_model_generation_seq') AS generation FROM serialised
),
current_consignments AS (
  SELECT c.created_at::date AS day, c.dangerous_goods, c.loading_country, c.unloading_country,
         c.transport_mode, c.main_transport_type, c.transport_reg_country
  FROM consignments c
  WHERE c.status = 'ACTIVE'
    AND NOT EXISTS (
      SELECT 1 FROM consignments c2
      WHERE c2.platform_id = c.platform_id
        AND c2.dataset_id  = c.dataset_id
        AND (c2.created_at, c2.revision) > (c.created_at, c.revision)
    )
),
snapshot AS (
  INSERT INTO rm_consignment_summary (generation, subset, dimension, dim_value, day, consignment_count)
  SELECT g.generation, v.subset, v.dimension, v.dim_value, cc.day, count(*)
  FROM new_generation g, current_consignments cc
  CROSS JOIN LATERAL (VALUES
    ('',     'total',               ''),
    ('EU02', 'dangerous_goods',     COALESCE(cc.dangerous_goods, '')),
    ('EU03', 'loading_country',     COALESCE(cc.loading_country, '')),
    ('EU03', 'unloading_country',   COALESCE(cc.unloading_country, '')),
    ('EU04', 'transport_mode',      COALESCE(cc.transport_mode, '')),
    ('EU04', 'main_transport_type', COALESCE(cc.main_transport_type, '')),
    ('EU04', 'transport_reg_country', COALESCE(cc.transport_reg_country, ''))
  ) AS v(subset, dimension, dim_value)
  GROUP BY g.generation, v.subset, v.dimension, v.dim_value, cc.day
  RETURNING 1
),
published AS (
  INSERT INTO read_model_pointer (model, generation)
  SELECT 'consignment_summary', generation FROM new_generation
  RETURNING generation
)
SELECT
  (SELECT generation FROM published) AS generation,
  (SELECT count(*) FROM snapshot)    AS row_count;
