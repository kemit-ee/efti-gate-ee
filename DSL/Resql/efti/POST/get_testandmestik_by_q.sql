SELECT id, data, hash
FROM testandmestik
WHERE id = (:q)::int;
