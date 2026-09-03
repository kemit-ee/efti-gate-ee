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
SELECT
  -- Built here rather than in the DSL: reshaping a result array would need .map(), which no Ruuter
  -- DSL file in this repo uses, so engine support is unproven. A JSONB column comes back as a real
  -- nested object (authority/dataset.yml relies on that for platforms.headers), so the handler can
  -- return the rows verbatim. Gives the caller exactly what POST /xroad/v1/dataset expects as `uil`.
  jsonb_build_object(
    'gateId',     latest.gate_id,
    'platformId', latest.platform_id,
    'datasetId',  latest.dataset_id
  ) AS uil,
  latest.main_transport_id,
  latest.main_transport_type,
  latest.transport_reg_country,
  latest.transport_mode,
  latest.dangerous_goods,
  latest.acceptance_date,
  latest.acceptance_country,
  latest.delivery_date,
  latest.delivery_country,
  latest.loading_date,
  latest.loading_country,
  latest.unloading_date,
  latest.unloading_country,
  latest.used_equipment_ids,
  latest.used_equipment_categories,
  latest.used_equipment_countries,
  latest.carried_equipment_ids,
  latest.carried_equipment_categories,
  latest.status,
  latest.created_at
FROM (
  -- Columns are named rather than `*` for two reasons: `status::text` must happen here, because the
  -- bare column is the consignment_status ENUM and every other read file in DSL/Resql casts it (the
  -- Rust ReSql driver cannot map a dynamic enum OID); and naming them keeps the `xml` TEXT out of
  -- the DISTINCT ON sort, which would otherwise carry the whole blob per candidate row for nothing.
  SELECT DISTINCT ON (dataset_id, platform_id)
    dataset_id,
    platform_id,
    gate_id,
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
  -- Narrows candidates through the three identifier indexes WITHOUT filtering the rows the
  -- DISTINCT ON sees. A consignment qualifies if ANY of its rows ever carried this identifier; that
  -- consignment's LATEST row is then resolved unfiltered, and the outer WHERE requires the latest
  -- row to still carry it.
  --
  -- Filtering on the identifier inside this subquery would repeat the soft-delete-bypass class of
  -- bug fixed in get_authority_by_registry_code.sql: consignments is append-only, so a dataset
  -- re-uploaded with a corrected plate or a swapped container keeps its old row, and DISTINCT ON
  -- over the *filtered* set would return that stale row — the consignment would keep answering to
  -- an identifier it no longer carries.
  WHERE (dataset_id, platform_id) IN (
    SELECT dataset_id, platform_id
    FROM consignments
    WHERE main_transport_id = :transport_means_id
       OR :transport_means_id = ANY(used_equipment_ids)
       OR :transport_means_id = ANY(carried_equipment_ids)
  )
  ORDER BY dataset_id, platform_id, created_at DESC
) latest
-- The latest row must still carry the identifier, on whichever of the three it was found.
WHERE (latest.main_transport_id = :transport_means_id
       OR :transport_means_id = ANY(latest.used_equipment_ids)
       OR :transport_means_id = ANY(latest.carried_equipment_ids))
  -- Positive allowlist, deliberately stricter than get_consignments.sql's `status != 'DELETED'`
  -- (which also returns INACTIVE). An INACTIVE consignment is not current knowledge, and a future
  -- status cannot leak by default. Consequence to be aware of: results can differ from the admin
  -- search for the same identifier.
  AND latest.status = 'ACTIVE'
  -- An absent OR empty country_code means "any country". Empty is handled here rather than
  -- normalised to NULL in the DSL: doing it there needs a ternary, which contains ": " and so
  -- terminates a YAML plain scalar, and quoting it would turn the null branch into the string
  -- "null" and silently match no rows at all.
  --
  -- Note this filters the TRANSPORT MEANS registration country, so it is meaningful for a plate and
  -- largely meaningless for a container id; a caller searching equipment should omit it.
  AND (:country_code IS NULL OR :country_code = '' OR latest.transport_reg_country = :country_code)
ORDER BY latest.created_at DESC
-- Server-fixed, NOT caller-supplied. A common identifier can match many consignments, and a
-- caller-controlled limit is how an identifier lookup turns into a bulk-export tool.
LIMIT 50;
