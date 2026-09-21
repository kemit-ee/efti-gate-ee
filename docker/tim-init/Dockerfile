FROM debian:bookworm-slim

RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends openssl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

COPY docker/tim-init/init.sh /init.sh
RUN chmod +x /init.sh

ENTRYPOINT ["/init.sh"]
