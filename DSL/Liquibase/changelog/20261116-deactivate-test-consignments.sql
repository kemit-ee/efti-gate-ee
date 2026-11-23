--liquibase formatted sql

-- The test consignments (datasetId 11111111-0000-4000-8000-0000000000NN) are no longer loaded by the
-- migration. The store is append-only, so an environment that loaded them gets a newer INACTIVE row
-- per dataset: they stop matching ACTIVE lookups and stay in the history. Where they were never
-- loaded the SELECT is empty and nothing is written.

--changeset efti:deactivate-test-consignments context:dev,prelive
INSERT INTO consignments (dataset_id, platform_id, gate_id, xml, status)
SELECT DISTINCT ON (dataset_id, platform_id) dataset_id, platform_id, gate_id, xml, 'INACTIVE'
FROM consignments
WHERE dataset_id::text LIKE '11111111-0000-4000-8000-0000000000%'
ORDER BY dataset_id, platform_id, created_at DESC, revision DESC;
