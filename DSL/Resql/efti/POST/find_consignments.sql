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
WHERE (:transportMode IS NULL OR :transportMode->>'operator' = 'EQ' AND transport_mode = :transportMode->>'mode' OR :transportMode->>'operator' = 'NE' AND transport_mode != :transportMode->>'mode')
  AND (:acceptanceDate IS NULL
       OR :acceptanceDate->0->>'operator' = 'EQ' AND acceptance_date = :acceptanceDate->0->>'date'
       OR :acceptanceDate->0->>'operator' = 'NE' AND acceptance_date != :acceptanceDate->0->>'date'
       OR :acceptanceDate->0->>'operator' = 'LT' AND acceptance_date < :acceptanceDate->0->>'date'
       OR :acceptanceDate->0->>'operator' = 'LE' AND acceptance_date <= :acceptanceDate->0->>'date'
       OR :acceptanceDate->0->>'operator' = 'GT' AND acceptance_date > :acceptanceDate->0->>'date'
       OR :acceptanceDate->0->>'operator' = 'GE' AND acceptance_date >= :acceptanceDate->0->>'date'
  )
  AND (:acceptanceCountry IS NULL OR :acceptanceCountry->>'operator' = 'EQ' AND acceptance_country = :acceptanceCountry->>'country' OR :acceptanceCountry->>'operator' = 'NE' AND acceptance_country != :acceptanceCountry->>'country')
  AND (:deliveryDate IS NULL OR :deliveryDate->0->>'operator' = 'EQ' AND delivery_date = :deliveryDate->0->>'date' OR :deliveryDate->0->>'operator' = 'NE' AND delivery_date != :deliveryDate->0->>'date')
  AND (:deliveryCountry IS NULL OR :deliveryCountry->>'operator' = 'EQ' AND delivery_country = :deliveryCountry->>'country' OR :deliveryCountry->>'operator' = 'NE' AND delivery_country != :deliveryCountry->>'country')
  AND (:dangerousGoods IS NULL OR :dangerousGoods->>'operator' = 'EQ' AND dangerous_goods = :dangerousGoods->>'code' OR :dangerousGoods->>'operator' = 'NE' AND dangerous_goods != :dangerousGoods->>'code')
  AND (:mainTransportId IS NULL OR :mainTransportId->>'operator' = 'EQ' AND main_transport_id = :mainTransportId->>'id' OR :mainTransportId->>'operator' = 'NE' AND main_transport_id != :mainTransportId->>'id')
  AND (:mainTransportType IS NULL OR :mainTransportType->>'operator' = 'EQ' AND main_transport_type = :mainTransportType->>'code' OR :mainTransportType->>'operator' = 'NE' AND main_transport_type != :mainTransportType->>'code')
  AND (:transportRegCountry IS NULL OR :transportRegCountry->>'operator' = 'EQ' AND transport_reg_country = :transportRegCountry->>'country' OR :transportRegCountry->>'operator' = 'NE' AND transport_reg_country != :transportRegCountry->>'country')
  AND (:loadingDate IS NULL OR :loadingDate->0->>'operator' = 'EQ' AND loading_date = :loadingDate->0->>'date' OR :loadingDate->0->>'operator' = 'NE' AND loading_date != :loadingDate->0->>'date')
  AND (:loadingDate IS NULL OR :loadingDate->1->>'operator' = 'EQ' AND loading_date = :loadingDate->1->>'date' OR :loadingDate->1->>'operator' = 'NE' AND loading_date != :loadingDate->1->>'date')
  AND (:loadingCountry IS NULL OR :loadingCountry->>'operator' = 'EQ' AND loading_country = :loadingCountry->>'country' OR :loadingCountry->>'operator' = 'NE' AND loading_country != :loadingCountry->>'country')
  AND (:unloadingDate IS NULL OR :unloadingDate->0->>'operator' = 'EQ' AND unloading_date = :unloadingDate->0->>'date' OR :unloadingDate->0->>'operator' = 'NE' AND unloading_date != :unloadingDate->0->>'date')
  AND (:unloadingDate IS NULL OR :unloadingDate->1->>'operator' = 'EQ' AND unloading_date = :unloadingDate->1->>'date' OR :unloadingDate->1->>'operator' = 'NE' AND unloading_date != :unloadingDate->1->>'date')
  AND (:unloadingCountry IS NULL OR :unloadingCountry->>'operator' = 'EQ' AND unloading_country = :unloadingCountry->>'country' OR :unloadingCountry->>'operator' = 'NE' AND unloading_country != :unloadingCountry->>'country')
  AND (:usedEquipmentIds IS NULL OR :usedEquipmentIds->>'operator' = 'EQ' AND used_equipment_ids @> :usedEquipmentIds->>'id'::text[] OR :usedEquipmentIds->>'operator' = 'NE' AND NOT used_equipment_ids @> :usedEquipmentIds->>'id'::text[])
  AND (:usedEquipmentCategories IS NULL OR :usedEquipmentCategories->>'operator' = 'EQ' AND used_equipment_categories @> :usedEquipmentCategories->>'code'::text[] OR :usedEquipmentCategories->>'operator' = 'NE' AND NOT used_equipment_categories @> :usedEquipmentCategories->>'code'::text[])
  AND (:usedEquipmentCountries IS NULL OR :usedEquipmentCountries->>'operator' = 'EQ' AND used_equipment_countries @> :usedEquipmentCountries->>'country'::text[] OR :usedEquipmentCountries->>'operator' = 'NE' AND NOT used_equipment_countries @> :usedEquipmentCountries->>'country'::text[])
  AND (:usedEquipmentSeq IS NULL OR :usedEquipmentSeq->>'operator' = 'EQ' AND used_equipment_seq @> :usedEquipmentSeq->>'sequence'::text[] OR :usedEquipmentSeq->>'operator' = 'NE' AND NOT used_equipment_seq @> :usedEquipmentSeq->>'sequence'::text[])
  AND (:carriedEquipmentIds IS NULL OR :carriedEquipmentIds->>'operator' = 'EQ' AND carried_equipment_ids @> :carriedEquipmentIds->>'id'::text[] OR :carriedEquipmentIds->>'operator' = 'NE' AND NOT carried_equipment_ids @> :carriedEquipmentIds->>'id'::text[])
  AND (:carriedEquipmentCategories IS NULL OR :carriedEquipmentCategories->>'operator' = 'EQ' AND carried_equipment_categories @> :carriedEquipmentCategories->>'code'::text[] OR :carriedEquipmentCategories->>'operator' = 'NE' AND NOT carried_equipment_categories @> :carriedEquipmentCategories->>'code'::text[])
  AND (:carriedEquipmentSeq IS NULL OR :carriedEquipmentSeq->>'operator' = 'EQ' AND carried_equipment_seq @> :carriedEquipmentSeq->>'sequence'::text[] OR :carriedEquipmentSeq->>'operator' = 'NE' AND NOT carried_equipment_seq @> :carriedEquipmentSeq->>'sequence'::text[])
ORDER BY platform_id, dataset_id, created_at DESC;
