SELECT * FROM (
  SELECT DISTINCT ON (platform_id, dataset_id)
    row_id,
    dataset_id,
    platform_id,
    gate_id,
    xml,
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
    created_by,
    created_at
  FROM consignments
  ORDER BY platform_id, dataset_id, created_at DESC
) latest
WHERE (:transport_mode IS NULL OR transport_mode = :transport_mode)
  AND (:acceptance_date IS NULL OR acceptance_date = :acceptance_date)
  AND (:acceptance_country IS NULL OR acceptance_country = :acceptance_country)
  AND (:delivery_date IS NULL OR delivery_date = :delivery_date)
  AND (:delivery_country IS NULL OR delivery_country = :delivery_country)
  AND (:dangerous_goods IS NULL OR dangerous_goods = :dangerous_goods)
  AND (:main_transport_id IS NULL OR main_transport_id = :main_transport_id)
  AND (:main_transport_type IS NULL OR main_transport_type = :main_transport_type)
  AND (:transport_reg_country IS NULL OR transport_reg_country = :transport_reg_country)
  AND (:loading_date IS NULL OR loading_date = :loading_date)
  AND (:loading_country IS NULL OR loading_country = :loading_country)
  AND (:unloading_date IS NULL OR unloading_date = :unloading_date)
  AND (:unloading_country IS NULL OR unloading_country = :unloading_country)
  AND (:used_equipment_ids IS NULL OR used_equipment_ids @> :used_equipment_ids::text[])
  AND (:used_equipment_categories IS NULL OR used_equipment_categories @> :used_equipment_categories::text[])
  AND (:used_equipment_countries IS NULL OR used_equipment_countries @> :used_equipment_countries::text[])
  AND (:used_equipment_seq IS NULL OR used_equipment_seq @> :used_equipment_seq::text[])
  AND (:carried_equipment_ids IS NULL OR carried_equipment_ids @> :carried_equipment_ids::text[])
  AND (:carried_equipment_categories IS NULL OR carried_equipment_categories @> :carried_equipment_categories::text[])
  AND (:carried_equipment_seq IS NULL OR carried_equipment_seq @> :carried_equipment_seq::text[])
ORDER BY platform_id, dataset_id, created_at DESC;
