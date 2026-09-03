FROM turnerrainer/resql:0.1.1-alpha

COPY --chown=resql:resql resql.yaml /app/resql.yaml
COPY --chown=resql:resql DSL/Resql /app/sql

ENV RUST_BACKTRACE=1
