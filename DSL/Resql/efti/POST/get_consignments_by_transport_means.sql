/*
description: Identifier-level lookup of consignments by transport-means identifier — a road
  registration plate, a vessel IMO, an aircraft registration, or a transport-equipment (container)
  id. Backs the X-Road transport-means service.
params:
  transport_means_id: { type: string, required: true }
  country_code:       { type: string }
*/
-- WHY A CURATED PROJECTION RATHER THAN get_consignments.sql:
--
--   1. It returns a stable, explicit field contract to an external X-Road consumer. get_consignments
--      selects identifier metadata plus the raw `xml` blob, whose schema belongs to the platform
--      (FTI004UploadIdentifierRequest), not to this gate — so its shape can change under us.
--   2. `consignments.xml` is redundant here: it is the *identifier* XML as received from the platform
--      (006-consignments.sql:54) and carries the same fields already denormalised into the columns
--      below. Shipping it would roughly double the response for no new information, on a route meant
--      to be cheap and high-volume.
--
-- NOT because it would leak dataset content: it would not. Dataset content never enters Postgres —
-- authority/dataset.yml fetches it from the platform with `?subsetId=...`, and that is where subset
-- entitlement is enforced. An earlier version of this comment claimed reusing get_consignments would
-- "bypass authorities.subsets"; that was wrong, and the same wrong premise was used to accuse
-- authority/search.yml of the same thing. Corrected here and in ADR-006.
--
-- MATCHES THREE IDENTIFIER FAMILIES, not just the towing unit:
--   main_transport_id      the main carriage transport means (road plate, IMO, aircraft reg)
--   used_equipment_ids     transport equipment in use (container / swap-body / trailer ids)
--   carried_equipment_ids  equipment carried on the transport means
-- A border check on a container number must not answer "not registered" just because the number is
-- on the equipment rather than the tractor. All three are indexed —
-- idx_consignments_main_transport_id (btree) and idx_consignments_{used,carried}_equip_ids (GIN).
--
-- Matching is CASE-SENSITIVE: these are TEXT / TEXT[] columns, not CITEXT, so '123abc' does not match
-- '123ABC'. Documented in openapi.yaml rather than normalised here, because changing it means a
-- column type change or functional indexes on three columns.
-- LATEST-ROW SEMANTICS: a consignment is returned only if its LATEST row (created_at DESC, revision
-- DESC) still carries the identifier and is ACTIVE. Expressed as "a matching ACTIVE row that has no
-- newer row for its (platform_id, dataset_id)" — the same self-correlated anti-join as
-- get_consignments.sql (ADR-009). A matching row with a newer sibling is a superseded version and is
-- dropped, so a corrected plate or a soft-delete still hides the old identifier.
--
-- The inner query BitmapOrs the three identifier indexes and sorts only those matches; the anti-join
-- (Index Only Scan on idx_consignments_dataset_latest) then runs over that ordered stream and stops
-- at the 50-row cut. Keeping the sort inside the subquery fixes this order for generic plans too,
-- which would otherwise probe every match of a shared equipment id before sorting.
SELECT
  -- Built here rather than in the DSL: reshaping a result array would need .map(), which no Ruuter
  -- DSL file in this repo uses, so engine support is unproven. A JSONB column comes back as a real
  -- nested object (authority/dataset.yml relies on that for platforms.headers), so the handler can
  -- return the rows verbatim. Gives the caller exactly what POST /xroad/v1/dataset expects as `uil`.
  jsonb_build_object(
    'gateId',     m.gate_id,
    'platformId', m.platform_id,
    'datasetId',  m.dataset_id
  ) AS uil,
  m.main_transport_id,
  m.main_transport_type,
  m.transport_reg_country,
  m.transport_mode,
  m.dangerous_goods,
  m.acceptance_date,
  m.acceptance_country,
  m.delivery_date,
  m.delivery_country,
  m.loading_date,
  m.loading_country,
  m.unloading_date,
  m.unloading_country,
  m.used_equipment_ids,
  m.used_equipment_categories,
  m.used_equipment_countries,
  m.carried_equipment_ids,
  m.carried_equipment_categories,
  m.status,
  m.created_at
FROM (
  -- Columns are named rather than `*`: `status::text` must happen here, because the bare column is
  -- the consignment_status ENUM and every other read file in DSL/Resql casts it (the Rust ReSql
  -- driver cannot map a dynamic enum OID); and naming them keeps the `xml` TEXT out of the sort.
  SELECT
    dataset_id,
    platform_id,
    gate_id,
    revision,
    main_transport_id,
    main_transport_type,
    transport_reg_country,
    transport_mode,
    dangerous_goods,
    acceptance_date,
    acceptance_country,
    delivery_date,
    delivery_country,
    loading_date,
    loading_country,
    unloading_date,
    unloading_country,
    used_equipment_ids,
    used_equipment_categories,
    used_equipment_countries,
    carried_equipment_ids,
    carried_equipment_categories,
    status::text AS status,
    created_at
  FROM consignments
  WHERE (main_transport_id = :transport_means_id
         OR used_equipment_ids @> ARRAY[:transport_means_id]
         OR carried_equipment_ids @> ARRAY[:transport_means_id])
    -- Positive allowlist, deliberately stricter than get_consignments.sql's `status != 'DELETED'`
    -- (which also returns INACTIVE). An INACTIVE consignment is not current knowledge, and a future
    -- status cannot leak by default. Consequence to be aware of: results can differ from the admin
    -- search for the same identifier.
    AND status = 'ACTIVE'
    -- An absent OR empty country_code means "any country". Empty is handled here rather than
    -- normalised to NULL in the DSL: doing it there needs a ternary, which contains ": " and so
    -- terminates a YAML plain scalar, and quoting it would turn the null branch into the string
    -- "null" and silently match no rows at all.
    --
    -- Note this filters the TRANSPORT MEANS registration country, so it is meaningful for a plate and
    -- largely meaningless for a container id; a caller searching equipment should omit it.
    AND (:country_code IS NULL OR :country_code = '' OR transport_reg_country = :country_code)
  ORDER BY created_at DESC, revision DESC
) m
WHERE NOT EXISTS (
  SELECT 1 FROM consignments c2
  WHERE c2.platform_id = m.platform_id
    AND c2.dataset_id  = m.dataset_id
    AND (c2.created_at, c2.revision) > (m.created_at, m.revision)
)
ORDER BY m.created_at DESC, m.revision DESC
-- Server-fixed, NOT caller-supplied. A common identifier can match many consignments, and a
-- caller-controlled limit is how an identifier lookup turns into a bulk-export tool.
LIMIT 50;
