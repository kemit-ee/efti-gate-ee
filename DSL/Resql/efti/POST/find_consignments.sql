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
WHERE (:transportMode->>'mode' IS NULL OR transport_mode = :transportMode->>'mode')
  AND (:acceptanceDate->0->>'date' IS NULL OR acceptance_date = :acceptanceDate->0->>'date')
  AND (:acceptanceDate->1->>'date' IS NULL OR acceptance_date = :acceptanceDate->1->>'date')
  AND (:acceptanceCountry->>'country' IS NULL OR acceptance_country = :acceptanceCountry->>'country')
  AND (:deliveryDate->0->>'date' IS NULL OR delivery_date = :deliveryDate->0->>'date')
  AND (:deliveryDate->1->>'date' IS NULL OR delivery_date = :deliveryDate->1->>'date')
  AND (:deliveryCountry->>'country' IS NULL OR delivery_country = :deliveryCountry->>'country')
  AND (:dangerousGoods->>'code' IS NULL OR dangerous_goods = :dangerousGoods->>'code')
  AND (:mainTransportId->>'id' IS NULL OR main_transport_id = :mainTransportId->>'id')
  AND (:mainTransportType->>'code' IS NULL OR main_transport_type = :mainTransportType->>'code')
  AND (:transportRegCountry->>'country' IS NULL OR transport_reg_country = :transportRegCountry->>'country')
  AND (:loadingDate->0->>'date' IS NULL OR loading_date = :loadingDate->0->>'date')
  AND (:loadingDate->1->>'date' IS NULL OR loading_date = :loadingDate->1->>'date')
  AND (:loadingCountry->>'country' IS NULL OR loading_country = :loadingCountry->>'country')
  AND (:unloadingDate->0->>'date' IS NULL OR unloading_date = :unloadingDate->0->>'date')
  AND (:unloadingDate->1->>'date' IS NULL OR unloading_date = :unloadingDate->1->>'date')
  AND (:unloadingCountry->>'country' IS NULL OR unloading_country = :unloadingCountry->>'country')
  AND (:usedEquipmentIds->>'id' IS NULL OR used_equipment_ids @> :usedEquipmentIds->>'id'::text[])
  AND (:usedEquipmentCategories->>'code' IS NULL OR used_equipment_categories @> :usedEquipmentCategories->>'code'::text[])
  AND (:usedEquipmentCountries->>'country' IS NULL OR used_equipment_countries @> :usedEquipmentCountries->>'country'::text[])
  AND (:usedEquipmentSeq->>'sequence' IS NULL OR used_equipment_seq @> :usedEquipmentSeq->>'sequence'::text[])
  AND (:carriedEquipmentIds->>'id' IS NULL OR carried_equipment_ids @> :carriedEquipmentIds->>'id'::text[])
  AND (:carriedEquipmentCategories->>'code' IS NULL OR carried_equipment_categories @> :carriedEquipmentCategories->>'code'::text[])
  AND (:carriedEquipmentSeq->>'sequence' IS NULL OR carried_equipment_seq @> :carriedEquipmentSeq->>'sequence'::text[])
ORDER BY platform_id, dataset_id, created_at DESC;
