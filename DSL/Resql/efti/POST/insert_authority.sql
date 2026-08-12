INSERT INTO authorities (id, country_code, name, subsets, is_active)
VALUES (
  :id,
  :countryCode,
  :name,
  :subsets::text[],
  COALESCE(:isActive, true)
)
RETURNING
  row_id,
  id,
  country_code,
  name,
  subsets,
  is_active,
  created_at;
