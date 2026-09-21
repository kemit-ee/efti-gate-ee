FROM turnerrainer/tim:0.4.1-alpha

# Distroless base (gcr.io/distroless/cc-debian12:nonroot, UID 65532) since
# 0.4.1-alpha: no shell, no apt, no tini. CA-trust setup and JWT key
# generation that used to happen in an entrypoint.sh now happen in the
# `tim-init` sidecar (docker/tim-init/), which writes into the volumes
# mounted below before this container starts.
COPY --chown=65532:65532 docker/tim/tim.yaml /app/tim.yaml

ENTRYPOINT ["/app/tim", "--config", "/app/tim.yaml"]
