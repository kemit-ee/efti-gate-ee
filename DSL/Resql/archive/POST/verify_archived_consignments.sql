/*
description: (archive DB) confirm the given row_ids are present AND their payload reads back —
  proof the batch reached cold storage and is accessible there, before the live rows are deleted.
params:
  rowIds: { type: array, items: { type: string } }
*/
SELECT
  cardinality(:rowIds::uuid[])                        AS requested_count,
  count(*)                                            AS confirmed_count,
  COALESCE(array_agg(row_id::text ORDER BY row_id), '{}') AS confirmed_row_ids
FROM consignments
WHERE row_id = ANY(:rowIds::uuid[])
  AND xml IS NOT NULL
  AND octet_length(xml) > 0;
