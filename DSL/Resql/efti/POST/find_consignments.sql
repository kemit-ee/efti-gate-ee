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
WHERE (:transportMode IS NULL OR transport_mode = :transportMode)
  AND (:acceptanceDate IS NULL OR acceptance_date = :acceptanceDate)
  AND (:acceptanceCountry IS NULL OR acceptance_country = :acceptanceCountry)
  AND (:deliveryDate IS NULL OR delivery_date = :deliveryDate)
  AND (:deliveryCountry IS NULL OR delivery_country = :deliveryCountry)
  AND (:dangerousGoods IS NULL OR dangerous_goods = :dangerousGoods)
  AND (:mainTransportId IS NULL OR main_transport_id = :mainTransportId)
  AND (:mainTransportType IS NULL OR main_transport_type = :mainTransportType)
  AND (:transportRegCountry IS NULL OR transport_reg_country = :transportRegCountry)
  AND (:loadingDate IS NULL OR loading_date = :loadingDate)
  AND (:loadingCountry IS NULL OR loading_country = :loadingCountry)
  AND (:unloadingDate IS NULL OR unloading_date = :unloadingDate)
  AND (:unloadingCountry IS NULL OR unloading_country = :unloadingCountry)
  AND (:usedEquipmentIds IS NULL OR used_equipment_ids @> :usedEquipmentIds::text[])
  AND (:usedEquipmentCategories IS NULL OR used_equipment_categories @> :usedEquipmentCategories::text[])
  AND (:usedEquipmentCountries IS NULL OR used_equipment_countries @> :usedEquipmentCountries::text[])
  AND (:usedEquipmentSeq IS NULL OR used_equipment_seq @> :usedEquipmentSeq::text[])
  AND (:carriedEquipmentIds IS NULL OR carried_equipment_ids @> :carriedEquipmentIds::text[])
  AND (:carriedEquipmentCategories IS NULL OR carried_equipment_categories @> :carriedEquipmentCategories::text[])
  AND (:carriedEquipmentSeq IS NULL OR carried_equipment_seq @> :carriedEquipmentSeq::text[])
ORDER BY platform_id, dataset_id, created_at DESC;
