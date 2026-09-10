/*
description: bulk-insert consignments rows carried over Ruuter from the live DB into the archive DB, idempotent via ON CONFLICT (row_id) DO NOTHING
params:
  rows: { type: array }
*/
WITH incoming AS (
  SELECT * FROM jsonb_to_recordset(:rows) AS x(
    row_id                        uuid,
    dataset_id                    uuid,
    platform_id                   text,
    gate_id                       text,
    xml                           text,
    status                        text,
    transport_mode                text,
    acceptance_date               timestamptz,
    acceptance_country            text,
    delivery_date                 timestamptz,
    delivery_country              text,
    dangerous_goods               text,
    main_transport_id             text,
    main_transport_type           text,
    transport_reg_country         text,
    loading_date                  timestamptz,
    loading_country               text,
    unloading_date                timestamptz,
    unloading_country             text,
    used_equipment_ids            text[],
    used_equipment_categories     text[],
    used_equipment_countries      text[],
    used_equipment_seq            integer[],
    carried_equipment_ids         text[],
    carried_equipment_categories  text[],
    carried_equipment_seq         integer[],
    created_at                    timestamptz
  )
),
ins AS (
  INSERT INTO consignments (
    row_id, dataset_id, platform_id, gate_id, xml, status, transport_mode,
    acceptance_date, acceptance_country, delivery_date, delivery_country, dangerous_goods,
    main_transport_id, main_transport_type, transport_reg_country,
    loading_date, loading_country, unloading_date, unloading_country,
    used_equipment_ids, used_equipment_categories, used_equipment_countries, used_equipment_seq,
    carried_equipment_ids, carried_equipment_categories, carried_equipment_seq,
    created_at, archived_at
  )
  SELECT
    row_id, dataset_id, platform_id, gate_id, xml, status, transport_mode,
    acceptance_date, acceptance_country, delivery_date, delivery_country, dangerous_goods,
    main_transport_id, main_transport_type, transport_reg_country,
    loading_date, loading_country, unloading_date, unloading_country,
    used_equipment_ids, used_equipment_categories, used_equipment_countries, used_equipment_seq,
    carried_equipment_ids, carried_equipment_categories, carried_equipment_seq,
    created_at, now()
  FROM incoming
  ON CONFLICT (row_id) DO NOTHING
  RETURNING row_id
)
SELECT
  (SELECT count(*) FROM incoming) AS received_count,
  (SELECT count(*) FROM ins)      AS inserted_count;
