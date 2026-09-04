FROM turnerrainer/ruuter:0.9.7-rc

COPY ruuter-xroad-mock.yaml /app/ruuter.yaml
COPY constants-xroad-mock.ini /app/constants.ini
COPY DSL/Ruuter-xroad-mock /app/DSL

# The base image ships demo projects under /app/DSL; COPY merges rather than replaces,
# so drop them or they get served on our port.
RUN rm -rf /app/DSL/samples /app/DSL/guarded-demo

ENV RUST_BACKTRACE=1
