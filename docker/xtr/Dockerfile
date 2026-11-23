FROM turnerrainer/xtr:0.5.1-rc

# The base image ships demo DSLs and WSDLs (ariregister, kliima, ...) under /app; COPY merges rather
# than replaces, so clear them first or they get served on our port.
RUN rm -rf /app/DSL /app/wsdl

# Config and DSLs are read once at boot, so a change needs `docker compose up --build xtr`.
# The mTLS keystore and the Security Server CA are NOT baked in: they are mounted at /app/ssl
# (compose.yml) or come from a Secret in the deployment.
COPY --chown=xtr:xtr xtr/xtr.yaml /app/xtr.yaml
COPY --chown=xtr:xtr xtr/DSL /app/DSL
