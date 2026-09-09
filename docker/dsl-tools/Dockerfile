# Ruuter's own DSL static-analysis + scenario-runner binaries, built from the same
# tag as the runtime image (docker/ruuter/Dockerfile). Bump both together.
#
#   dsl-lint  — YAML parse, unknown step keys, dangling next:, empty DSL,
#               unresolved [#constant], missing template targets, reachability.
#   dsl-test  — in-process scenario runner over *.test.yml (DSL-tests/).
#
# Used by the dsl-validate CI job (.github/workflows/e2e.yml) and the
# validate:dsl GitLab job (.gitlab-ci.yml). Not a runtime service.
FROM rust:1.88-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends \
        git pkg-config libssl-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
ARG RUUTER_TAG=v0.9.12-rc
RUN git clone --depth 1 --branch "${RUUTER_TAG}" https://github.com/turnerrainer/Ruuter.git .
RUN cargo build --release --bin dsl-lint --bin dsl-test

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends libssl3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/release/dsl-lint /usr/local/bin/dsl-lint
COPY --from=build /src/target/release/dsl-test /usr/local/bin/dsl-test
WORKDIR /workdir
ENTRYPOINT []
CMD ["dsl-lint", "--help"]
