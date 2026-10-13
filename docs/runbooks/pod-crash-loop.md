# Container/pod crash loop

## Symptoms
- `docker compose ps` shows a service cycling `Restarting`/`Exited`, or (in Kubernetes) `CrashLoopBackOff`.
- Dependent services fail to start because a `depends_on: service_healthy` upstream never goes healthy.

## Diagnosis
1. `docker compose ps` — identify which of the 15 services is looping.
2. `docker compose logs --tail=200 <service>` — read the last startup attempt in full; most of these
   services fail fast with a clear message.
3. Known per-service causes:
   - **`ruuter` / `ruuter-xroad-mock`** — a DSL YAML syntax error or a bad `constants.ini`/`ruuter.yaml`
     edit. `constants.ini`/`ruuter.yaml` are `COPY`-ed at build time with no env substitution — a
     `docker compose up --build ruuter` was skipped after an edit (see AGENTS.md "Key gotchas").
   - **`resql`** — distroless image, UID 65532; config/SQL files not readable by that UID will fail
     silently at startup. No shell/curl available inside the container to debug interactively.
   - **`edelivery`** — missing/misnamed AS4 keystore, wrong `KEYSTORE_PASSWORD`, or alias ≠ `OWN_GATE_ID`;
     see [certificate-expiry.md](certificate-expiry.md) for the exact log line to check
     (`partyId: <OWN_GATE_ID>, KeyIdentifier/SKI: …, SerialNumber: …`).
   - **`tim`** — distroless + non-root; depends on `tim-init` completing successfully
     (`depends_on: tim-init: condition: service_completed_successfully`). If `tim-init` failed, `tim`
     never gets a trusted CA bundle or JWT signing key. Use the `tim healthcheck` subcommand, not `curl`
     (none is installed).
   - **`registry`** — this one fails at **image build time**, not at container start (ADR-011 §3): a bad
     `registry/**/*.yml` fails `scripts/registry-to-json.py` during `docker build`, so a running
     container crash-looping here most likely means a stale/partial image — rebuild with
     `docker compose up --build registry` and read the build log, not the container log.
   - **`liquibase`** — one-shot migration job; a failed changelog blocks every dependent service from
     starting (`depends_on`). Check `docker compose logs liquibase` for the specific changeset.
4. Check resource limits — an OOM-killed container (`docker compose logs` showing `Killed`, or
   `OOMKilled` in `kubectl describe pod`) looks identical to a logic crash at first glance.

## Resolution
1. Fix the root cause identified above (config, cert, YAML syntax, resources) — do not just keep
   restarting a looping container; it will loop again.
2. Rebuild the affected image if the fix touched `constants.ini`, `ruuter.yaml`, or `registry/**`
   (`--watch` only syncs `DSL/` — it does not pick up those files, per AGENTS.md).
3. Bring the stack back in dependency order if multiple services were affected:
   `docker compose up -d` (compose respects `depends_on`/healthchecks automatically).
4. Confirm recovery via the relevant health endpoint (`efti/health/{live,ready}`, `xroad/health/ready`,
   or `resql health`) before closing the incident.

## Escalation
- Ops engineer handles restarts/rebuilds for config and resource issues.
- Escalate to gate maintainers for anything pointing at a DSL/application bug (bad YAML that passed
  review, a Kotlin service crashing on valid input).
- Escalate to the platform/infra owner for OOM/resource-limit tuning in the target environment.

## Dashboards / queries
- Prometheus's `up{job=~"edelivery|xml-mapper"}` (the `ServiceUnreachable` alert,
  `docker/prometheus/alert-rules.yml`) catches the case where the crash loop stops a service from
  answering scrapes at all — but there's no cAdvisor/container-restart-count metric, so a loop that
  still answers `/metrics` between restarts won't show up there. Today: `docker compose ps` +
  `docker compose logs` (or `kubectl get pods` / `kubectl logs --previous` on Kubernetes) remain the
  primary signal.
