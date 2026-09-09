#!/bin/sh
# End-to-end check of the consignments archive flow (copy -> verify -> real DELETE), run against a
# dev stack. The e2e http-tests only cover the guard + the "nothing to archive" path because they
# cannot create rows dated in the past; this script inserts aged fixtures and drives the full sweep.
#
#   docker compose -f compose.yml up -d --wait
#   sh docs/architecture/infrastructure/archive-flow-test.sh
#
# Expects: 2 rows moved (D1/V1, D2/W1); a re-run moves 0; the current row of every dataset —
# including D2's DELETED tombstone — stays in public.consignments; live DELETE is real.

set -e
NET=${NET:-efti_gate_ee_default}
DB="docker compose -f compose.yml exec -T database psql -U efti -d efti"
ADB="docker compose -f compose.yml exec -T archive-database psql -U efti -d efti_archive"
CURL="docker run --rm --network $NET curlimages/curl -s"
TOKEN="dev-archive-ops-token-change-me"

$DB -c "
TRUNCATE public.consignments; TRUNCATE jobs_execution_log;
INSERT INTO public.consignments (dataset_id, platform_id, gate_id, xml, status, main_transport_id, created_at) VALUES
 ('d1111111-1111-1111-1111-111111111111','p1','EU-EE','<x>V1</x>','ACTIVE','V1',         now()-interval '90 days'),
 ('d1111111-1111-1111-1111-111111111111','p1','EU-EE','<x>V2</x>','ACTIVE','V2',         now()-interval '60 days'),
 ('d1111111-1111-1111-1111-111111111111','p1','EU-EE','<x>V3</x>','ACTIVE','V3-current', now()-interval '1 day'),
 ('d2222222-2222-2222-2222-222222222222','p1','EU-EE','<x>W1</x>','ACTIVE', 'W1',         now()-interval '120 days'),
 ('d2222222-2222-2222-2222-222222222222','p1','EU-EE','<x>W2</x>','DELETED','W2-tomb',    now()-interval '100 days');"
$ADB -c "TRUNCATE consignments;"

echo "--- sweep (expect archived=2 deleted=2) ---"
$CURL -X POST http://ruuter:8086/ops/v1/archive-consignments -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'; echo
echo "--- re-run (expect all 0) ---"
$CURL -X POST http://ruuter:8086/ops/v1/archive-consignments -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'; echo

echo "--- live public.consignments (expect V2, V3-current, W2-tomb) ---"
$DB -c "SELECT dataset_id, main_transport_id, status::text FROM public.consignments ORDER BY dataset_id, created_at;"
echo "--- archive DB (expect V1, W1) ---"
$ADB -c "SELECT dataset_id, main_transport_id, archived_at::date FROM consignments ORDER BY dataset_id, created_at;"
echo "--- get_archived_consignment for D2 (cold-storage read) ---"
$CURL -X POST http://resql:8090/efti/get_archived_consignment -H 'Content-Type: application/json' -d '{"datasetId":"d2222222-2222-2222-2222-222222222222"}'; echo
echo "--- jobs_execution_log ---"
$DB -c "SELECT job_name, status::text, details FROM jobs_execution_log ORDER BY created_at;"
