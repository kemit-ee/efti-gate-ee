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
  :usedEquipmentSeq,
  :carriedEquipmentIds,
  :carriedEquipmentCategories,
  :carriedEquipmentSeq
)
RETURNING
  row_id,
  dataset_id,
  platform_id,
  gate_id,
  created_at;
