# Turvis trust view (ADR-011 amendment, SPEC section 7.2): the allowlist-filtered view of registry/
# as its own small static artifact, built by the same converter from the same revision as the
# `registry` image but shipped separately, so Turvis never needs a path to the apiKey-bearing
# `registry` service.
#
# Stage 1 is the converter and validator; stage 2 is stock nginx serving turvis-trust.json and
# health.json only. Only the allowlisted view (no platform data, no apiKey, no URLs) is copied into
# the final image; the registry JSON is not even generated here.
FROM python:3.13-alpine AS trust-build

RUN pip install --no-cache-dir pyyaml==6.0.2 cryptography==50.0.1

COPY scripts/registry-to-json.py /registry-to-json.py
COPY registry/ /registry/

# Same revision as the registry image: pass the identical SOURCE_REVISION build argument to both.
ARG SOURCE_REVISION=unknown
RUN python3 /registry-to-json.py --source /registry --trust-out /srv/trust --source-revision "$SOURCE_REVISION"


FROM nginx:stable-alpine

COPY docker/turvis-trust/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=trust-build /srv/trust /usr/share/nginx/html

# Internal only; a deployment that delivers the artifact by volume needs no network path at all.
EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
