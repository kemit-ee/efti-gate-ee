# ADR-015 — the registry service: serves registry/** as JSON over HTTP, internal-only.
# Plain python3 + stdlib (json/http.server): nothing to install, nothing to pin beyond the base
# image. Runs as UID 65532 to match the non-root posture of the other images in this repo.
FROM python:3.13-alpine

RUN adduser -D -H -u 65532 registry

COPY docker/registry/serve.py /serve.py
RUN chmod 0555 /serve.py

USER 65532
EXPOSE 8080

ENTRYPOINT ["python3", "/serve.py"]
