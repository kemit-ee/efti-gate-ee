/*
description: status of the latest follow up log row of one follow-up (same queryId, destination platform, dataset request and content), used to tell a finished repeat from one still in flight (#354)
params:
  contentJson: { type: string, required: true }
  datasetRequestId: { type: string, required: true }
  destinationPlatformId: { type: string, required: true }
  queryId: { type: string, required: true }
*/
SELECT status::text
FROM follow_up_log
WHERE query_id = :queryId::uuid
  AND destination_platform_id = :destinationPlatformId
  AND dataset_request_id = :datasetRequestId::uuid
  AND content_hash = encode(sha256(convert_to(:contentJson, 'UTF8')), 'hex')
ORDER BY revision DESC
LIMIT 1;
