/*
description: the real DELETE — physically removes the given consignments rows from the live DB.
  A row is removed ONLY if it still has a NEWER sibling row for the same (platform_id, dataset_id),
  so the current row of a dataset (incl. a DELETED tombstone) can never be deleted. The archive
  flow calls this only after verifying every row_id reached cold storage.
params:
  rowIds: { type: array, items: { type: string } }
*/
DELETE FROM consignments c
WHERE c.row_id = ANY(:rowIds::uuid[])
  AND EXISTS (
    SELECT 1 FROM consignments n
    WHERE n.platform_id = c.platform_id
      AND n.dataset_id  = c.dataset_id
      AND n.created_at   > c.created_at
  )
RETURNING c.row_id::text AS row_id;
