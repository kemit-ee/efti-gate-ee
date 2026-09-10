# Re-running these benchmarks

```sh
docker compose -f compose.yml up -d --wait

# seed one realistic consignment the real way — POST sample.xml through the platform route
# (VESSEL-001, gate EU-EE; xml-mapper + insert_consignment do the column mapping)
NET=efti_gate_ee_default
docker run --rm --network $NET -v "$PWD/code/xml-mapper/xsd/FTI004:/x" curlimages/curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST http://ruuter:8086/platforms/v1/consignments -H 'Content-Type: text/xml' \
  -H 'X-Api-Key: mock-secret-key' --data-binary @/x/sample.xml

# semantic fixtures (append-only re-upload AAA->BBB, single CCC) — SQL only, no XML route for this
docker compose -f compose.yml exec -T database psql -U efti -d efti < docs/performance/askend_perf_verification/seed-consignments.sql

# ab sidecar on the compose network
docker run -d --name ab --network $NET -v "$PWD/docs/askend_performance:/d" httpd:2.4-alpine sleep infinity

# 4a — full route
docker exec ab ab -q -T application/json -H 'X-Request-ID:073b32bc-a081-11f1-8d67-3c9c0f2eb459' \
  -H 'X-Internal-Service-Token:dev-internal-service-token-change-me' -p /d/search.json \
  -n 1000 -c 100 http://ruuter:8086/efti/api/v1/authority/search

# 4b — ReSql only
docker exec ab ab -q -T application/json -p /d/resql-search.json \
  -n 2000 -c 50 http://resql:8090/efti/get_consignments

# 4c/4d — 1M rows
docker compose -f compose.yml exec -T database psql -U efti -d efti -f - < docs/performance/askend_perf_verification/bulk-insert-1m.sql
```
