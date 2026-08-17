#!/usr/bin/env bash
# Käivitab e2e smoke-testid lokaalselt.
# Kasutab 19xxx porte, et mitte konflikti tekitada töötava dev-stackiga.
set -euo pipefail

export EFTI_DB_PORT="${EFTI_DB_PORT:-19432}"
export EFTI_RESQL_PORT="${EFTI_RESQL_PORT:-19090}"
export EFTI_RUUTER_PORT="${EFTI_RUUTER_PORT:-19086}"

COMPOSE="docker compose -f compose.yml -f compose.ci.yml"
RUUTER_URL="http://localhost:${EFTI_RUUTER_PORT}"
MAX_WAIT=120
INTERVAL=3

cleanup() {
  echo ""
  echo "Peatame teenused..."
  $COMPOSE down -v 2>/dev/null || true
}
trap cleanup EXIT

echo "==> Build"
$COMPOSE build database liquibase resql ruuter

echo ""
echo "==> Start"
$COMPOSE up -d database liquibase resql ruuter

echo ""
echo "==> Ootan ruuterit (max ${MAX_WAIT}s)..."
elapsed=0
until curl -sf "${RUUTER_URL}/efti/api/v1/test/baasikontoroll" > /dev/null 2>&1; do
  sleep $INTERVAL
  elapsed=$((elapsed + INTERVAL))
  echo "   ${elapsed}s / ${MAX_WAIT}s"
  if [ $elapsed -ge $MAX_WAIT ]; then
    echo "Ruuter ei vastanud ${MAX_WAIT}s jooksul — logid:"
    $COMPOSE logs
    exit 1
  fi
done
echo "   Ruuter valmis (${elapsed}s)"

echo ""
echo "==> Newman"
npx --yes newman run tests/e2e/baasikontoroll.postman_collection.json \
  --env-var "base_url=${RUUTER_URL}"
