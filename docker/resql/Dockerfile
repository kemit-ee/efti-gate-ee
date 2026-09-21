FROM turnerrainer/resql:0.4.3-alpha

COPY --chown=65532:65532 resql.yaml /app/resql.yaml
COPY --chown=65532:65532 DSL/Resql /app/sql

ENV RUST_BACKTRACE=1
