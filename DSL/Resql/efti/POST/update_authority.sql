INSERT INTO authorities (id, country_code, name, subsets, is_active)
VALUES (
  :id,
  :countryCode,
  :name,
  :subsets,
  true
)
RETURNING
  row_id,
  id,
  country_code,
  name,
  subsets,
  is_active AS is_authority_active,
  created_at;
