/*
description: ADR-014 registry sync — reconcile the authorities table with registry/authorities/*.json.
  Same declarative/append-only contract as sync_gates and sync_platforms. Subsets are normalised
  (de-duplicated, sorted ascending) before both the comparison and the insert, so re-ordering the
  same set in a file is not a change and does not append a revision.
  A file may declare status DELETED to keep an explicit tombstone for an authority that must never
  be reusable; such a file is stable (declared DELETED == latest DELETED == no write).
  Called by the one-shot registry-sync container at startup.
params:
  entities: { type: string, required: true }
*/
WITH desired AS (
  SELECT
    e.id,
    e.name           AS name,
    e."registryCode" AS registry_code,
    COALESCE(
      (SELECT array_agg(DISTINCT s.value ORDER BY s.value)
       FROM jsonb_array_elements_text(COALESCE(e."subsets", '[]'::jsonb)) AS s(value)),
      ARRAY[]::text[]
    ) AS subsets,
    e.status
  FROM jsonb_to_recordset(COALESCE(:entities, '[]')::jsonb) AS e(
    id             text,
    name           text,
    "registryCode" text,
    "subsets"      jsonb,
    status         text
  )
),
latest AS (
  SELECT DISTINCT ON (id)
    id, name, registry_code, subsets, status
  FROM authorities
  ORDER BY id, created_at DESC, revision DESC
),
changed AS (
  SELECT d.id, d.name, d.registry_code, d.subsets, d.status
  FROM desired d
  LEFT JOIN latest l ON l.id = d.id
  WHERE l.id IS NULL
     OR l.name          IS DISTINCT FROM d.name
     OR l.registry_code IS DISTINCT FROM d.registry_code
     OR l.subsets       IS DISTINCT FROM d.subsets
     OR l.status::text  IS DISTINCT FROM d.status
),
upserted AS (
  INSERT INTO authorities (id, name, registry_code, subsets, status)
  SELECT id, name, registry_code, subsets, status::authority_status
  FROM changed
  RETURNING id
),
deleted AS (
  INSERT INTO authorities (id, name, registry_code, subsets, status)
  SELECT l.id, l.name, l.registry_code, l.subsets, 'DELETED'::authority_status
  FROM latest l
  WHERE l.status != 'DELETED'
    AND NOT EXISTS (SELECT 1 FROM desired d WHERE d.id = l.id)
  RETURNING id
)
SELECT
  (SELECT count(*) FROM desired)  AS declared,
  (SELECT count(*) FROM upserted) AS upserted,
  (SELECT count(*) FROM deleted)  AS deleted;
