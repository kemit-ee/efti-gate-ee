# ADR-014 — one-shot loader that applies registry/** to the database through ReSql.
# Plain python3 + stdlib only (json/urllib): nothing to install, nothing to pin beyond the base
# image. Runs as UID 65532 to match the non-root posture of the other images in this repo.
FROM python:3.13-alpine

RUN adduser -D -H -u 65532 registry-sync

COPY docker/registry-sync/sync.py /sync.py
RUN chmod 0555 /sync.py

USER 65532

ENTRYPOINT ["python3", "/sync.py"]
