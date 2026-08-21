#!/bin/sh
set -e

VAULT=/service/vault

# Generate the self-signed CA, the HTTPS cert and the id_token signing pair on first
# start (and again after `docker compose down -v`). On later starts the named volume
# already holds them, so skip — regenerating would invalidate TIM's imported CA.
if [ ! -f "${VAULT}/https.crt" ]; then
    echo "[tara-mock] Generating cryptographic keys..."
    mkdir -p "${VAULT}"
    cd /genkeys && sh genkeys.sh
fi

echo "[tara-mock] Starting TARA-Mock server..."
cd /service
exec /service/tara-mock-server -conf /service/config.json
