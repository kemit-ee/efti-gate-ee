# Bürokratt Ruuter (Java). Pinned to the same version the LJVIS reference stack runs,
# because the auth flow depends on Java-Ruuter-only features: `.guard` files, and
# `template:` steps with `requestType: templates`.
FROM ghcr.io/buerokratt/ruuter:v2.2.1
COPY DSL/Ruuter/efti /DSL/efti
COPY constants.ini /app/constants.ini
