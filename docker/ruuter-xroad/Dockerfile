FROM turnerrainer/ruuter:0.12.1-rc

COPY ruuter-xroad.yaml /app/ruuter.yaml
COPY DSL/Ruuter-xroad /app/DSL

# constants-xroad.ini holds INTERNAL_SERVICE_TOKEN, so it is mounted at runtime (compose.yml), not baked in.

# The base image ships demo projects under /app/DSL; COPY merges rather than replaces,
# so drop them or they get served on our port.
RUN rm -rf /app/DSL/samples /app/DSL/guarded-demo

ENV RUST_BACKTRACE=1
