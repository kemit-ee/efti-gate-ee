SELECT
  row_id,
  dataset_id,
  platform_id,
  gate_id,
  status::text,
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
  carried_equipment_seq,
  created_at
FROM (
  SELECT DISTINCT ON (platform_id, dataset_id) *
  FROM consignments
  ORDER BY platform_id, dataset_id, created_at DESC
) latest
WHERE (:status IS NULL OR :status = '' OR status::text = :status)
  AND (:platformId IS NULL OR :platformId = '' OR platform_id = :platformId)
  AND (:transportMode IS NULL OR :transportMode = '' OR transport_mode = :transportMode)
  AND (:dangerousGoods IS NULL OR :dangerousGoods = '' OR dangerous_goods = :dangerousGoods)
ORDER BY created_at DESC
LIMIT COALESCE(:limit, 20) OFFSET COALESCE(:offset, 0);
