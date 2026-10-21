# Turvis mock (SPEC docs/specs/turvis/SPEC.md, issue #306): the Turvis contract DSL served by a
# standalone Ruuter. DEVELOPMENT AND TEST ONLY: no real cryptography, a committed dev token, and no
# place in a production Compose set or Helm release. It has no database and no dependency on the
# gate; every route answers from the request or a canned literal.
FROM turnerrainer/ruuter:0.12.1-rc

COPY ruuter-turvis-mock.yaml /app/ruuter.yaml
COPY constants-turvis-mock.ini /app/constants.ini
COPY DSL/Ruuter-turvis-mock /app/DSL

# The base image ships demo projects under /app/DSL; COPY merges rather than replaces,
# so drop them or they get served on our port.
RUN rm -rf /app/DSL/samples /app/DSL/guarded-demo

ENV RUST_BACKTRACE=1
