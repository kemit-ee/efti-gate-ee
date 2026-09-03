/*
description: Resolve an ACTIVE authority by registry code and report whether every requested subset
  is within its permitted set. Backs the X-Road adapter's FORBIDDEN_SUBSET check.
params:
  registry_code:     { type: string, required: true }
  requested_subsets: { type: array, items: { type: string }, required: true }
*/
-- The containment test lives here rather than in the DSL because Postgres does it in one operator
-- (<@) while no Ruuter DSL file in the repo uses .every/.includes/arrow functions, so the engine's
-- JS array support is unproven. ReSql already handles array params and ::text[] casts — see
-- insert_authority.sql.
--
-- Same latest-row-then-filter shape as get_authority_by_registry_code.sql, for the same reason: the
-- DISTINCT ON must resolve each id's latest row BEFORE registry_code and status are applied, or a
-- soft-deleted authority with an older ACTIVE row would still resolve. The id IN (...) subquery
-- narrows candidates through idx_authorities_registry_code without filtering the rows DISTINCT ON
-- sees. No LIMIT 1: a registry code matching several ACTIVE authorities is a misconfiguration the
-- caller must reject, not silently resolve.
--
-- CAUTION: '{}' <@ anything is TRUE, so an empty requested_subsets returns allowed = true. The
-- caller must reject an empty or absent subset list before calling this (400 MISSING_SUBSET).
SELECT
  a.id            AS authority_id,
  a.registry_code,
  a.subsets,
  :requested_subsets::text[] <@ a.subsets            AS allowed,
  ARRAY(SELECT s
        FROM unnest(:requested_subsets::text[]) AS s
        EXCEPT
        SELECT unnest(a.subsets))                    AS denied_subsets
FROM (
  SELECT DISTINCT ON (id)
    id,
    registry_code,
    subsets,
    status::text AS status,
    created_at
  FROM authorities
  WHERE id IN (SELECT id FROM authorities WHERE registry_code = :registry_code)
  ORDER BY id, created_at DESC
) a
WHERE a.registry_code = :registry_code
  AND a.status = 'ACTIVE'
ORDER BY a.id;
