/*
description: insert consignment
params:
  acceptanceCountry: { type: string }
  acceptanceDate: { type: datetime }
  carriedEquipmentCategories: { type: array, items: { type: string } }
  carriedEquipmentIds: { type: array, items: { type: string } }
  carriedEquipmentSeq: { type: array, items: { type: integer } }
  dangerousGoods: { type: string }
  datasetId: { type: uuid, required: true }
  deliveryCountry: { type: string }
  deliveryDate: { type: datetime }
  gateId: { type: string }
  loadingCountry: { type: string }
  loadingDate: { type: datetime }
  mainTransportId: { type: string }
  mainTransportType: { type: string }
  platformId: { type: string }
  status: { type: string, default: "ACTIVE" }
  transportMode: { type: string }
  transportRegCountry: { type: string }
  unloadingCountry: { type: string }
  unloadingDate: { type: datetime }
  usedEquipmentCategories: { type: array, items: { type: string } }
  usedEquipmentCountries: { type: array, items: { type: string } }
  usedEquipmentIds: { type: array, items: { type: string } }
  usedEquipmentSeq: { type: array, items: { type: integer } }
  xml: { type: string }
*/
-- The *Seq params are declared `items: {type: integer}` (matching the XML `<SequenceNumeric>` and
-- the XML mapper's `List<Int>`), so ReSql binds them as a native `bigint[]`. The columns are
-- `INTEGER[]` (see 006-consignments.sql), and Postgres will not implicitly narrow `bigint[]` →
-- `integer[]` on INSERT — hence the explicit `::integer[]` casts. The `text[]` params match their
-- `TEXT[]` columns directly and need no cast. (ReSql >= 0.1.2-alpha binds empty typed arrays
-- natively too, so this is cast-only, not a jsonb-unpack workaround — see issue #132.)
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
) VALUES (
  :datasetId::uuid,
  :platformId,
  :gateId,
  :xml,
  COALESCE(:status, 'ACTIVE')::consignment_status,
  :transportMode,
  :acceptanceDate::timestamptz,
  :acceptanceCountry,
  :deliveryDate::timestamptz,
  :deliveryCountry,
  :dangerousGoods,
  :mainTransportId,
  :mainTransportType,
  :transportRegCountry,
  :loadingDate::timestamptz,
  :loadingCountry,
  :unloadingDate::timestamptz,
  :unloadingCountry,
  :usedEquipmentIds,
  :usedEquipmentCategories,
  :usedEquipmentCountries,
  :usedEquipmentSeq::integer[],
  :carriedEquipmentIds,
  :carriedEquipmentCategories,
  :carriedEquipmentSeq::integer[]
)
RETURNING
  row_id,
  dataset_id,
  platform_id,
  gate_id,
  created_at;
