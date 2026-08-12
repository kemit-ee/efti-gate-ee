INSERT INTO authorities (id, country_code, name, subsets)
VALUES (
  :id,
  :countryCode,
  :name,
  ARRAY(SELECT jsonb_array_elements_text(:subsets::jsonb))
)
RETURNING
  row_id,
  id,
  country_code,
  name,
  subsets,
  is_active,
  created_at;
