/*
description: the real DELETE — physically removes the given consignments rows from the live DB.
  SQL-guarded so a row is deleted ONLY if it is already present in archive.consignments AND still
  has a newer sibling row (i.e. it is genuinely superseded, never a current row / tombstone).
params:
  rowIds: { type: array, items: { type: string } }
*/
DELETE FROM consignments c
USING archive.consignments a
WHERE c.row_id = a.row_id
  AND c.row_id = ANY(:rowIds::uuid[])
  AND EXISTS (
    SELECT 1 FROM consignments n
    WHERE n.platform_id = c.platform_id
      AND n.dataset_id  = c.dataset_id
      AND n.created_at   > c.created_at
  )
RETURNING c.row_id::text AS row_id;
