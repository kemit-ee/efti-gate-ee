#!/bin/sh
# Per-hop latency of the authority/search local-hit path, measured sequentially (c=1)
# so each number is one request's cost, not queue depth under load.
#
#   ab -> ruuter /health/ready      bare Ruuter routing + HTTP, no DSL downstream
#   ab -> resql -> db               ReSql parse/bind + Postgres (get_consignments)
#   ab -> ruuter -> resql -> db     full authority/search: guard + DSL steps + the
#                                   internal http hop to ReSql + Postgres
#
# ruuter_dsl_overhead ~= mean(stage 3) - mean(stage 2) - mean(stage 1)
#
# Prereqs: stack up (compose.yml), data seeded (run.md), and an `ab` sidecar on the
# compose network with docs/askend_performance mounted at /d:
#   docker run -d --name ab --network efti_gate_ee_default \
#     -v "$PWD/docs/askend_performance:/d" httpd:2.4-alpine sleep infinity
#   docker cp docs/askend_performance/hop-latency.sh ab:/hop.sh && docker exec ab sh /hop.sh
#
# Ruuter also logs per-step timing itself; watch it alongside:
#   docker compose -f compose.yml logs -f ruuter | grep -E 'local_search|check_|respond_|authority/search 200'

N=${1:-3000}
WARM=200
H1='X-Request-ID:073b32bc-a081-11f1-8d67-3c9c0f2eb459'
H2='X-Internal-Service-Token:dev-internal-service-token-change-me'

row() {
  label=$1; shift
  ab -q -c 1 -n "$WARM" "$@" >/dev/null 2>&1
  ab -q -c 1 -n "$N" "$@" 2>&1 | awk -v l="$label" '
    /Time per request/ && /mean\)/ && !/across/ {mean=$4}
    /Failed requests/ {fail=$3}
    /Non-2xx/ {n2=$3}
    /^  50%/ {p50=$2} /^  90%/ {p90=$2} /^  99%/ {p99=$2} /longest/ {max=$2}
    /Requests per second/ {rps=$4}
    END {printf "%-28s mean=%-8s p50=%-6s p90=%-6s p99=%-7s max=%-7s  %8s rps  fail=%s non2xx=%s\n", \
                 l, mean, p50, p90, p99, max, rps, fail, (n2==""?0:n2)}'
}

echo "n=$N per stage, c=1, after $WARM warmup"
row "ruuter /health/ready"    http://ruuter:8086/efti/health/ready
row "resql get_consignments"  -T application/json -p /d/resql-search.json http://resql:8090/efti/get_consignments
row "ruuter authority/search" -T application/json -H "$H1" -H "$H2" -p /d/search.json http://ruuter:8086/efti/api/v1/authority/search
