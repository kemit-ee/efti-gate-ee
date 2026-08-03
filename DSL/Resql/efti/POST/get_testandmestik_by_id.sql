SELECT id, hash, data
FROM testandmestik
WHERE id = (:id)::int;
