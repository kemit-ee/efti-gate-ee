/*
description: confirm the given row_ids are present AND readable in the archive DB (proof the data
  was moved elsewhere and is accessible from there, before the live rows are deleted)
params:
  rowIds: { type: array, items: { type: string } }
*/
SELECT
  cardinality(:rowIds::uuid[])                       AS requested_count,
  count(*)                                           AS confirmed_count,
  array_agg(row_id::text ORDER BY row_id)            AS confirmed_row_ids
FROM archive.consignments
WHERE row_id = ANY(:rowIds::uuid[])
  AND xml IS NOT NULL
  AND octet_length(xml) > 0;
