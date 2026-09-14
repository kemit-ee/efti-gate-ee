/*
description: soft delete consignment
params:
  datasetId: { type: string, required: true }
  platformId: { type: string, required: true }
  gateId: { type: string, required: true }
*/
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
  'DELETED'::consignment_status,
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
FROM (
  SELECT DISTINCT ON (dataset_id, platform_id)
    *
  FROM consignments
  WHERE dataset_id = :datasetId::uuid AND platform_id = :platformId
  ORDER BY dataset_id, platform_id, created_at DESC, row_id DESC
) latest
WHERE latest.gate_id = :gateId AND latest.status != 'DELETED'
RETURNING
  row_id,
  dataset_id,
  status::text,
  created_at;
