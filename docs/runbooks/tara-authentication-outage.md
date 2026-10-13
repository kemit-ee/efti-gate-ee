# TARA authentication outage

## Symptoms
- Admin UI users can't log in; `/auth/callback` fails, or TIM returns an OIDC error.
- Before PR #176, an unreachable TIM/TARA caused the UI to redirect-loop forever; that's fixed, but the
  underlying "TIM/TARA unreachable" condition can still happen.
- `GET /auth/user` returns 401 for everyone, not just one user (a single broken JWT/token is a user
  problem, not an outage).

## Diagnosis
1. `docker compose ps tim tara-mock tim-database` (dev) — is `tim` healthy? Its healthcheck is `/app/tim
   healthcheck` (distroless image, no curl/shell).
2. `docker compose logs tim` — look for OIDC client errors (`TIM_TARA_CLIENT_ID`/`TIM_TARA_CLIENT_SECRET`
   mismatch), introspection failures (`TIM_INTROSPECT_RUUTER_SECRET`), or TLS trust errors (dev: missing
   `/tim-ca-bundle/ca-bundle.pem` — see [certificate-expiry.md](certificate-expiry.md)).
3. In production, TIM points at the real TARA endpoint — check whether the outage is TARA itself (a
   national incident, out of this team's control) vs. our own TIM/network configuration. TARA's own
   status is not something this gate can introspect; treat "TARA is down nationally" and "our TIM can't
   reach TARA" as different incidents requiring different responses.
4. Confirm it isn't scoped to one user: a revoked token or a `tara_sub` change intentionally invalidates
   that one user's session (AGENTS.md: "changing `tara_sub` sets a revocation cutoff") — that's expected
   behavior, not an outage.

## Resolution
1. If `tim`/`tara-mock` (dev) is down: `docker compose restart tim-init tim` (tim-init must complete
   before tim starts) or `docker compose up --build tim-init tim tara-mock` if config changed.
2. If TARA itself is down nationally: there's no workaround that doesn't weaken security —
   **`DEV_LOGIN_ENABLED`/`dev-login` is a local-development-only bypass, defaults to `false` in the
   production Docker build, and must stay that way; do not enable it in production as an outage
   workaround.** Communicate the outage and wait for TARA to recover.
3. If it's TIM's own config (client id/secret, introspection secret): fix the env var and restart `tim` —
   do not just restart it repeatedly without fixing the config, the OIDC handshake will fail again
   immediately.

## Escalation
- Ops engineer checks TIM/tara-mock container health and logs first.
- Escalate to the platform/infra owner for TIM's own infra (its database, secrets, network path to the
  real TARA).
- Escalate to gate maintainers for anything in our OIDC client configuration or the `auth/` Ruuter
  routes.
- A national TARA outage is outside this team's control — escalate/communicate per the organisation's
  incident process rather than trying to engineer around it.

## Dashboards / queries
- Not yet available — TIM/tara-mock don't expose a Prometheus `/metrics` endpoint today (only
  `edelivery`/`xml-mapper` do, see `docs/architecture/observability/monitoring_and_alerting.md`). The
  only current signal is `docker compose logs tim tara-mock` and the `tim healthcheck` container status.
