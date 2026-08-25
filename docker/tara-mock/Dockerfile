# TARA-Mock — local stand-in for the Estonian TARA OIDC broker.
# Build context: repo root (both the `tara-mock/` submodule and `docker/tara-mock/` must be visible).
#
# Stage 1: compile the binary so container start is instant (`go run .` is slow).
FROM golang:latest AS builder
WORKDIR /build
COPY tara-mock/service /build
RUN CGO_ENABLED=0 GOOS=linux go build -o /tara-mock-server .

# Stage 2: lean runtime — the binary plus openssl, which genkeys.sh needs.
FROM debian:bookworm-slim
RUN apt-get update && \
    apt-get install -y --no-install-recommends openssl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /service

COPY --from=builder /tara-mock-server /service/tara-mock-server
COPY tara-mock/service               /service
COPY tara-mock/genkeys               /genkeys

# Override v3.ext so the generated HTTPS cert carries every SAN we need:
# `localhost` for the host/browser hop, `tara-mock` for TIM's Docker-DNS back-channel calls.
COPY docker/tara-mock/v3.ext         /genkeys/v3.ext

COPY docker/tara-mock/entrypoint.sh  /entrypoint.sh
RUN chmod +x /entrypoint.sh /service/tara-mock-server

# /service/vault is the `tara-mock-vault` named volume — TIM mounts it read-only to pick up rootCA.pem.
# /service/identities.json is bind-mounted from docker/tara-mock/identities.json at runtime.
VOLUME ["/service/vault"]

EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
