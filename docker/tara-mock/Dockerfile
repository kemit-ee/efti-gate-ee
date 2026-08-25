# Build context: efti-gate-ee root (both submodule and docker/tara-mock/ are accessible)
#
# Stage 1: compile TARA-Mock binary (avoids slow `go run .` on every container start)
FROM golang:latest AS builder
WORKDIR /build
COPY tara-mock/service /build
RUN CGO_ENABLED=0 GOOS=linux go build -o /tara-mock-server .

# Stage 2: lean runtime image — only openssl (for genkeys.sh) + compiled binary
FROM debian:bookworm-slim
RUN apt-get update && \
    apt-get install -y --no-install-recommends openssl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /service

COPY --from=builder /tara-mock-server /service/tara-mock-server
COPY tara-mock/service               /service
COPY tara-mock/genkeys               /genkeys

# Override v3.ext so the generated HTTPS cert includes all required SANs
# (localhost for the browser, tara-mock for Docker-internal TIM calls)
COPY docker/tara-mock/v3.ext         /genkeys/v3.ext

COPY docker/tara-mock/entrypoint.sh  /entrypoint.sh
RUN chmod +x /entrypoint.sh /service/tara-mock-server

# /service/vault is always a Docker named volume (shared with TIM for CA cert)
# /service/identities.json is mounted from docker/tara-mock/identities.json at runtime
VOLUME ["/service/vault"]

EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
