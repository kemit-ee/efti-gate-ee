#!/usr/bin/env bash
# Status-code checks of docs/test-cases/xroad-provide.md.
# usage: xroad-smoke.sh [base]   (default prelive)
# Behind the gateway /health/ready is 404; against the pod directly (localhost:8087) it is 200.
B=${1:-https://eftiprelive.xtpnl.kemitaws.ee/xroad}
C="EE/GOV/70000099/testSubsystem"
n=0
t(){ # name expected method path [body] [client]
  n=$((n+1)); local name=$1 exp=$2 m=$3 p=$4 body=${5:-} cl=${6-$C}
  local args=(-sS -m 10 -o /tmp/xs.out -w "%{http_code}" -X "$m" -H "Content-Type: application/json" -H "X-Road-Id: $(printf "0a11ce5e-%04d-4d1d-8f1a-0ec1%08d" $n $n)")
  [ -n "$cl" ] && args+=(-H "X-Road-Client: $cl")
  [ -n "$body" ] && args+=(-d "$body")
  code=$(curl "${args[@]}" "$B$p" 2>/dev/null); [ "$code" = "$exp" ] && r=OK || r=FAIL
  printf "%-4s %-34s exp=%s got=%s  %s\n" $r "$name" $exp "$code" "$(head -c 90 /tmp/xs.out | tr '\n' ' ')"
}
t openapi-public 200 GET /provide/openapi "" ""
t health-not-exposed 404 GET /health/ready "" ""
t echo-ok 200 POST /provide/v1/echo '{"hello":"world"}'
t echo-no-client 401 POST /provide/v1/echo '{}' ""
t echo-unknown-client 403 POST /provide/v1/echo '{}' "EE/GOV/99999999/x"
t subsets 200 GET /provide/v1/subsets
t tm-existence 200 POST /provide/v1/transport-means '{"identifier":"VESSEL-001","scope":"existence"}' "EE/GOV/70000093/ants"
t tm-missing-identifier 400 POST /provide/v1/transport-means '{}' "EE/GOV/70000093/ants"
t dataset-bad-body 400 POST /provide/v1/dataset '{}' "EE/GOV/70000093/ants"
t search-empty-criteria 200 POST /provide/v1/search '{}' "EE/GOV/70000099/testSubsystem"
