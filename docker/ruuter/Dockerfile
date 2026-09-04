FROM turnerrainer/ruuter:0.9.9-rc

COPY ruuter.yaml /app/ruuter.yaml
COPY constants.ini /app/constants.ini
COPY DSL/Ruuter /app/DSL

# The base image ships demo projects under /app/DSL; COPY merges rather than replaces,
# so drop them or they get served on our port.
RUN rm -rf /app/DSL/samples /app/DSL/guarded-demo

ENV RUST_BACKTRACE=1
