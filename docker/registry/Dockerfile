# ADR-011 — the registry delivery image: convert `registry/**/*.yml` to JSON at build time and
# serve the result with plain nginx. No application server, no runtime conversion, no mount.
#
# Stage 1 is the converter *and* the validator: a malformed or incomplete registry file fails the
# build, so an invalid registry can never be deployed. Stage 2 is stock nginx serving static files.
FROM python:3.13-alpine AS registry-build

# Pinned for reproducibility; the converter is the only thing that needs a YAML parser and an X.509
# parser (it also builds, and so validates, the Turvis trust view — see docker/turvis-trust/Dockerfile).
RUN pip install --no-cache-dir pyyaml==6.0.2 cryptography==50.0.1

COPY scripts/registry-to-json.py /registry-to-json.py
COPY registry/ /registry/

# Fails the build on any bad file (unknown key, missing required key, bad PEM/enum/id mismatch) or when
# the trust view cannot be built from it (expired/unparseable certificate, unknown chain anchor).
# health.json carries the generation of the trust view built from this same revision; pass the same
# SOURCE_REVISION build argument as to docker/turvis-trust/Dockerfile.
ARG SOURCE_REVISION=unknown
RUN python3 /registry-to-json.py --source /registry --out /srv/registry --source-revision "$SOURCE_REVISION"


FROM nginx:stable-alpine

COPY docker/registry/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=registry-build /srv/registry /usr/share/nginx/html

# Internal only. The served documents contain live platform API keys (ADR-011), so this port must
# never be published beyond the Compose network or exposed by an ingress.
EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
