/*
description: get consignments
params:
  gateId: { type: string }
  criteria: { type: object }
  limit: { type: number, default: 20 }
  offset: { type: number, default: 0 }
*/
SELECT
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
  created_at
-- ADR-009: filter first against the base table, then keep only the rows that are the current
-- version of their (platform_id, dataset_id). The self-correlated NOT EXISTS ("no newer row for
-- this dataset") replaces the old inner `SELECT DISTINCT ON (platform_id, dataset_id) * FROM
-- consignments ORDER BY ...`, which materialised the latest-per-dataset set over the WHOLE table
-- on every call (1M rows -> ~23s external-merge sort). Now the criteria predicate uses its btree
-- index and the anti-join is an Index Only Scan on idx_consignments_dataset_latest. Append-only
-- and "no cross-table JOINs on the hot path" both preserved. Semantically identical to the old
-- query (docs/askend_performance/semantic-test.sql).
FROM consignments c
WHERE c.status != 'DELETED'
  AND NOT EXISTS (
    SELECT 1 FROM consignments c2
    WHERE c2.platform_id = c.platform_id
      AND c2.dataset_id  = c.dataset_id
      AND c2.created_at   > c.created_at
  )
  AND (:gateId IS NULL OR gate_id = :gateId)
  AND (:criteria->>'transportMode' IS NULL
       OR :criteria->'transportMode'->>'operator' = 'EQ' AND transport_mode = :criteria->'transportMode'->>'mode'
       OR :criteria->'transportMode'->>'operator' = 'NE' AND transport_mode != :criteria->'transportMode'->>'mode'
  )
  AND (:criteria->>'acceptanceCountry' IS NULL
       OR :criteria->'acceptanceCountry'->>'operator' = 'EQ' AND acceptance_country = :criteria->'acceptanceCountry'->>'country'
       OR :criteria->'acceptanceCountry'->>'operator' = 'NE' AND acceptance_country != :criteria->'acceptanceCountry'->>'country'
  )
  AND (:criteria->>'deliveryCountry' IS NULL
       OR :criteria->'deliveryCountry'->>'operator' = 'EQ' AND delivery_country = :criteria->'deliveryCountry'->>'country'
       OR :criteria->'deliveryCountry'->>'operator' = 'NE' AND delivery_country != :criteria->'deliveryCountry'->>'country'
  )
  AND (:criteria->>'dangerousGoodsCode' IS NULL
       OR :criteria->'dangerousGoodsCode'->>'operator' = 'EQ' AND dangerous_goods = :criteria->'dangerousGoodsCode'->>'code'
       OR :criteria->'dangerousGoodsCode'->>'operator' = 'NE' AND dangerous_goods != :criteria->'dangerousGoodsCode'->>'code'
  )
  AND (:criteria->>'mainTransportId' IS NULL
       OR :criteria->'mainTransportId'->>'operator' = 'EQ' AND main_transport_id = :criteria->'mainTransportId'->>'id'
       OR :criteria->'mainTransportId'->>'operator' = 'NE' AND main_transport_id != :criteria->'mainTransportId'->>'id'
  )
  AND (:criteria->>'mainTransportType' IS NULL
       OR :criteria->'mainTransportType'->>'operator' = 'EQ' AND main_transport_type = :criteria->'mainTransportType'->>'code'
       OR :criteria->'mainTransportType'->>'operator' = 'NE' AND main_transport_type != :criteria->'mainTransportType'->>'code'
  )
  AND (:criteria->>'transportRegCountry' IS NULL
       OR :criteria->'transportRegCountry'->>'operator' = 'EQ' AND transport_reg_country = :criteria->'transportRegCountry'->>'country'
       OR :criteria->'transportRegCountry'->>'operator' = 'NE' AND transport_reg_country != :criteria->'transportRegCountry'->>'country'
  )
  AND (:criteria->>'loadingCountry' IS NULL
       OR :criteria->'loadingCountry'->>'operator' = 'EQ' AND loading_country = :criteria->'loadingCountry'->>'country'
       OR :criteria->'loadingCountry'->>'operator' = 'NE' AND loading_country != :criteria->'loadingCountry'->>'country'
  )
  AND (:criteria->>'unloadingCountry' IS NULL
       OR :criteria->'unloadingCountry'->>'operator' = 'EQ' AND unloading_country = :criteria->'unloadingCountry'->>'country'
       OR :criteria->'unloadingCountry'->>'operator' = 'NE' AND unloading_country != :criteria->'unloadingCountry'->>'country'
  )
  AND (:criteria->>'usedEquipmentId' IS NULL
       OR :criteria->'usedEquipmentId'->>'operator' = 'EQ' AND :criteria->'usedEquipmentId'->>'id' = any(used_equipment_ids)
       OR :criteria->'usedEquipmentId'->>'operator' = 'NE' AND :criteria->'usedEquipmentId'->>'id' != any(used_equipment_ids)
  )
  AND (:criteria->>'usedEquipmentCategory' IS NULL
       OR :criteria->'usedEquipmentCategory'->>'operator' = 'EQ' AND :criteria->'usedEquipmentCategory'->>'code' = any(used_equipment_categories)
       OR :criteria->'usedEquipmentCategory'->>'operator' = 'NE' AND :criteria->'usedEquipmentCategory'->>'code' != any(used_equipment_categories)
  )
  AND (:criteria->>'usedEquipmentCountry' IS NULL
       OR :criteria->'usedEquipmentCountry'->>'operator' = 'EQ' AND :criteria->'usedEquipmentCountry'->>'country' = any(used_equipment_countries)
       OR :criteria->'usedEquipmentCountry'->>'operator' = 'NE' AND :criteria->'usedEquipmentCountry'->>'country' != any(used_equipment_countries)
  )
  AND (:criteria->>'usedEquipmentSeq' IS NULL
       OR :criteria->'usedEquipmentSeq'->>'operator' = 'EQ' AND (:criteria->'usedEquipmentSeq'->>'sequence')::integer = any(used_equipment_seq)
       OR :criteria->'usedEquipmentSeq'->>'operator' = 'NE' AND (:criteria->'usedEquipmentSeq'->>'sequence')::integer != any(used_equipment_seq)
  )
  AND (:criteria->>'carriedEquipmentId' IS NULL
       OR :criteria->'carriedEquipmentId'->>'operator' = 'EQ' AND :criteria->'carriedEquipmentId'->>'id' = any(carried_equipment_ids)
       OR :criteria->'carriedEquipmentId'->>'operator' = 'NE' AND :criteria->'carriedEquipmentId'->>'id' != any(carried_equipment_ids)
  )
  AND (:criteria->>'carriedEquipmentCategory' IS NULL
       OR :criteria->'carriedEquipmentCategory'->>'operator' = 'EQ' AND :criteria->'carriedEquipmentCategory'->>'code' = any(carried_equipment_categories)
       OR :criteria->'carriedEquipmentCategory'->>'operator' = 'NE' AND :criteria->'carriedEquipmentCategory'->>'code' != any(carried_equipment_categories)
  )
  AND (:criteria->>'carriedEquipmentSeq' IS NULL
       OR :criteria->'carriedEquipmentSeq'->>'operator' = 'EQ' AND (:criteria->'carriedEquipmentSeq'->>'sequence')::integer = any(carried_equipment_seq)
       OR :criteria->'carriedEquipmentSeq'->>'operator' = 'NE' AND (:criteria->'carriedEquipmentSeq'->>'sequence')::integer != any(carried_equipment_seq)
  )
  AND (:criteria->'acceptanceDate'->>0 IS NULL
       OR :criteria->'acceptanceDate'->0->>'operator' = 'EQ' AND acceptance_date = (:criteria->'acceptanceDate'->0->>'date')::timestamptz
       OR :criteria->'acceptanceDate'->0->>'operator' = 'NE' AND acceptance_date != (:criteria->'acceptanceDate'->0->>'date')::timestamptz
       OR :criteria->'acceptanceDate'->0->>'operator' = 'LT' AND acceptance_date < (:criteria->'acceptanceDate'->0->>'date')::timestamptz
       OR :criteria->'acceptanceDate'->0->>'operator' = 'LE' AND acceptance_date <= (:criteria->'acceptanceDate'->0->>'date')::timestamptz
       OR :criteria->'acceptanceDate'->0->>'operator' = 'GT' AND acceptance_date > (:criteria->'acceptanceDate'->0->>'date')::timestamptz
       OR :criteria->'acceptanceDate'->0->>'operator' = 'GE' AND acceptance_date >= (:criteria->'acceptanceDate'->0->>'date')::timestamptz
  )
  AND (:criteria->'acceptanceDate'->>1 IS NULL
       OR :criteria->'acceptanceDate'->1->>'operator' = 'LT' AND acceptance_date < (:criteria->'acceptanceDate'->1->>'date')::timestamptz
       OR :criteria->'acceptanceDate'->1->>'operator' = 'LE' AND acceptance_date <= (:criteria->'acceptanceDate'->1->>'date')::timestamptz
       OR :criteria->'acceptanceDate'->1->>'operator' = 'GT' AND acceptance_date > (:criteria->'acceptanceDate'->1->>'date')::timestamptz
       OR :criteria->'acceptanceDate'->1->>'operator' = 'GE' AND acceptance_date >= (:criteria->'acceptanceDate'->1->>'date')::timestamptz
  )
  AND (:criteria->'deliveryDate'->>0 IS NULL
       OR :criteria->'deliveryDate'->0->>'operator' = 'EQ' AND delivery_date = (:criteria->'deliveryDate'->0->>'date')::timestamptz
       OR :criteria->'deliveryDate'->0->>'operator' = 'NE' AND delivery_date != (:criteria->'deliveryDate'->0->>'date')::timestamptz
       OR :criteria->'deliveryDate'->0->>'operator' = 'LT' AND delivery_date < (:criteria->'deliveryDate'->0->>'date')::timestamptz
       OR :criteria->'deliveryDate'->0->>'operator' = 'LE' AND delivery_date <= (:criteria->'deliveryDate'->0->>'date')::timestamptz
       OR :criteria->'deliveryDate'->0->>'operator' = 'GT' AND delivery_date > (:criteria->'deliveryDate'->0->>'date')::timestamptz
       OR :criteria->'deliveryDate'->0->>'operator' = 'GE' AND delivery_date >= (:criteria->'deliveryDate'->0->>'date')::timestamptz
  )
  AND (:criteria->'deliveryDate'->>1 IS NULL
       OR :criteria->'deliveryDate'->1->>'operator' = 'LT' AND delivery_date < (:criteria->'deliveryDate'->1->>'date')::timestamptz
       OR :criteria->'deliveryDate'->1->>'operator' = 'LE' AND delivery_date <= (:criteria->'deliveryDate'->1->>'date')::timestamptz
       OR :criteria->'deliveryDate'->1->>'operator' = 'GT' AND delivery_date > (:criteria->'deliveryDate'->1->>'date')::timestamptz
       OR :criteria->'deliveryDate'->1->>'operator' = 'GE' AND delivery_date >= (:criteria->'deliveryDate'->1->>'date')::timestamptz
  )
  AND (:criteria->'loadingDate'->>0 IS NULL
       OR :criteria->'loadingDate'->0->>'operator' = 'EQ' AND loading_date = (:criteria->'loadingDate'->0->>'date')::timestamptz
       OR :criteria->'loadingDate'->0->>'operator' = 'NE' AND loading_date != (:criteria->'loadingDate'->0->>'date')::timestamptz
       OR :criteria->'loadingDate'->0->>'operator' = 'LT' AND loading_date < (:criteria->'loadingDate'->0->>'date')::timestamptz
       OR :criteria->'loadingDate'->0->>'operator' = 'LE' AND loading_date <= (:criteria->'loadingDate'->0->>'date')::timestamptz
       OR :criteria->'loadingDate'->0->>'operator' = 'GT' AND loading_date > (:criteria->'loadingDate'->0->>'date')::timestamptz
       OR :criteria->'loadingDate'->0->>'operator' = 'GE' AND loading_date >= (:criteria->'loadingDate'->0->>'date')::timestamptz
  )
  AND (:criteria->'loadingDate'->>1 IS NULL
       OR :criteria->'loadingDate'->1->>'operator' = 'LT' AND loading_date < (:criteria->'loadingDate'->1->>'date')::timestamptz
       OR :criteria->'loadingDate'->1->>'operator' = 'LE' AND loading_date <= (:criteria->'loadingDate'->1->>'date')::timestamptz
       OR :criteria->'loadingDate'->1->>'operator' = 'GT' AND loading_date > (:criteria->'loadingDate'->1->>'date')::timestamptz
       OR :criteria->'loadingDate'->1->>'operator' = 'GE' AND loading_date >= (:criteria->'loadingDate'->1->>'date')::timestamptz
  )
  AND (:criteria->'unloadingDate'->>0 IS NULL
       OR :criteria->'unloadingDate'->0->>'operator' = 'EQ' AND unloading_date = (:criteria->'unloadingDate'->0->>'date')::timestamptz
       OR :criteria->'unloadingDate'->0->>'operator' = 'NE' AND unloading_date != (:criteria->'unloadingDate'->0->>'date')::timestamptz
       OR :criteria->'unloadingDate'->0->>'operator' = 'LT' AND unloading_date < (:criteria->'unloadingDate'->0->>'date')::timestamptz
       OR :criteria->'unloadingDate'->0->>'operator' = 'LE' AND unloading_date <= (:criteria->'unloadingDate'->0->>'date')::timestamptz
       OR :criteria->'unloadingDate'->0->>'operator' = 'GT' AND unloading_date > (:criteria->'unloadingDate'->0->>'date')::timestamptz
       OR :criteria->'unloadingDate'->0->>'operator' = 'GE' AND unloading_date >= (:criteria->'unloadingDate'->0->>'date')::timestamptz
  )
  AND (:criteria->'unloadingDate'->>1 IS NULL
       OR :criteria->'unloadingDate'->1->>'operator' = 'LT' AND unloading_date < (:criteria->'unloadingDate'->1->>'date')::timestamptz
       OR :criteria->'unloadingDate'->1->>'operator' = 'LE' AND unloading_date <= (:criteria->'unloadingDate'->1->>'date')::timestamptz
       OR :criteria->'unloadingDate'->1->>'operator' = 'GT' AND unloading_date > (:criteria->'unloadingDate'->1->>'date')::timestamptz
       OR :criteria->'unloadingDate'->1->>'operator' = 'GE' AND unloading_date >= (:criteria->'unloadingDate'->1->>'date')::timestamptz
  )
ORDER BY created_at DESC
LIMIT COALESCE(:limit, 100) OFFSET COALESCE(:offset, 0);
