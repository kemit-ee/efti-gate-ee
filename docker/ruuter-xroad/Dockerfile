FROM turnerrainer/ruuter:0.9.7-rc
COPY DSL/Ruuter-xroad /app/DSL
COPY constants-xroad.ini /app/constants.ini
COPY ruuter-xroad.yaml /app/ruuter.yaml

# The base image ships demo projects under /app/DSL; COPY merges rather than replaces,
# so drop them or they get served on our port.
RUN rm -rf /app/DSL/samples /app/DSL/guarded-demo
