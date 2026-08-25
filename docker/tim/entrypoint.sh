#!/bin/sh
set -e

# Import TARA-Mock self-signed CA so TIM can verify OIDC HTTPS calls.
# rootCA.pem is produced by the tara-mock container on first boot and
# placed in the shared named volume mounted at /tara-mock-vault.
TARA_CA=/tara-mock-vault/rootCA.pem
MAX_WAIT=60
WAITED=0

if [ -n "$TARA_MOCK_CA_PATH" ]; then
    TARA_CA="$TARA_MOCK_CA_PATH"
fi

echo "[tim] Waiting for TARA-Mock CA certificate at ${TARA_CA}..."
until [ -f "$TARA_CA" ]; do
    if [ "$WAITED" -ge "$MAX_WAIT" ]; then
        echo "[tim] ERROR: TARA-Mock CA not found after ${MAX_WAIT}s — aborting."
        exit 1
    fi
    sleep 1
    WAITED=$((WAITED + 1))
done

echo "[tim] Importing TARA-Mock CA into OS trust store..."
cp "$TARA_CA" /usr/local/share/ca-certificates/tara-mock-ca.crt
update-ca-certificates --fresh > /dev/null 2>&1

# Generate RSA key if not already present (dev / CI convenience).
KEY=/opt/tim/keys/jwt-private.pem
if [ ! -f "$KEY" ]; then
    echo "[tim] Generating RSA private key at ${KEY}..."
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
        -out "$KEY" 2>/dev/null
    chmod 600 "$KEY"
fi

echo "[tim] Starting TIM..."
exec /app/tim --config /app/tim.yaml
