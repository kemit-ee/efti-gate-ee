SELECT * FROM (
  SELECT DISTINCT ON (id)
    row_id,
    id,
    name,
    registry_code,
    subsets,
    status::text,
    created_at
  FROM authorities
  ORDER BY id, created_at DESC
) latest
WHERE status != 'DELETED'
LIMIT COALESCE(:limit::int, 20) OFFSET COALESCE(:offset::int, 0);
