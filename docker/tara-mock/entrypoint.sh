#!/bin/sh
set -e

VAULT=/service/vault

# Generate keys on first start (or after `docker compose down -v`).
# On subsequent starts the named volume already contains the keys — skip.
if [ ! -f "${VAULT}/https.crt" ]; then
    echo "[tara-mock] Generating cryptographic keys..."
    mkdir -p "${VAULT}" /client/vault
    cd /genkeys && sh genkeys.sh
fi

echo "[tara-mock] Starting TARA-Mock server..."
cd /service
exec /service/tara-mock-server -conf /service/config.json
