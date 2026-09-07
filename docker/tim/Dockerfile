FROM turnerrainer/tim:0.3.0-alpha

USER root
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends openssl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/tim/keys && chmod 700 /opt/tim/keys

COPY docker/tim/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

COPY docker/tim/tim.yaml /app/tim.yaml

ENTRYPOINT ["/usr/bin/tini", "--", "/entrypoint.sh"]
