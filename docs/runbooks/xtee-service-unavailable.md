# X-Road (xroad) service unavailable

## Symptoms
- An X-Road authority member can't reach the gate through their Security Server — `transport-means`,
  `dataset`, `search`, or `follow-up` calls under `/xroad/v1/*` fail or time out.
- `GET /xroad/health/ready` (public, unguarded per `xroad/GET/health/.guard.yml`'s `override_ancestors`)
  returns non-200.
- Every authority is affected (points at the gate), vs. one authority specifically (points at their
  Security Server or their registry entry).

## Diagnosis
1. `curl` (from inside the network, not externally — **`/xroad/**` must never be exposed by the ingress;
   only the Security Server should be able to reach `ruuter-xroad` on port 8087**) `GET /xroad/health/ready`
   (on `ruuter-xroad`) and `GET /efti/health/ready` (on `ruuter`) — if `/xroad/health/ready` fails but the
   efti probe is up, check the `ruuter-xroad` container and its `allowed_urls`/`constants-xroad.ini`;
   if both are down, this is a general Ruuter outage, see
   [pod-crash-loop.md](pod-crash-loop.md)/[database-connectivity-loss.md](database-connectivity-loss.md)
   rather than an X-Road-specific issue.
2. If only `/xroad/**` is affected while `/efti/**` is fine: check `docker compose logs ruuter` for the
   specific request — common causes:
   - `X-Road-Client` member code doesn't resolve to any `registry/authorities/*.yml` `registryCode` —
     expected for an unregistered/mistyped authority, not a gate problem; confirm the authority's
     registry file exists and the code matches exactly.
   - `X-Road-Id` isn't a valid hex 8-4-4-4-12 UUID — the guard returns 400 `INVALID_REQUEST_ID`; this is
     a caller-side bug in the Security Server's request, not a gate outage.
   - A `FORBIDDEN_SUBSET` response — the authority is authenticating fine but querying a dataset subset
     their registry entry doesn't grant (`subsets` in `registry/authorities/<id>.yml`); this is an
     authorization decision working as intended, not an incident.
3. Confirm this is actually `xroad/` and not the **separate** `Ruuter-xroad-mock` container
   (`ruuter-xroad-mock:8088`, public developer sandbox, no DB/no core) — they're easy to confuse by name
   but are different services with different failure domains.

## Resolution
1. General Ruuter outage: follow [pod-crash-loop.md](pod-crash-loop.md) or
   [database-connectivity-loss.md](database-connectivity-loss.md) as appropriate.
2. Unregistered/misconfigured authority: this is a registry change, not a hotfix — add/correct the
   authority's `registry/authorities/<id>.yml`, commit, `docker compose up --build registry`.
3. Subset permission dispute: confirm with the authority what they expect to query vs. what their
   registry entry grants before changing anything — a permission change is a deliberate registry edit,
   not something to loosen reactively under incident pressure.
4. If the ingress is accidentally exposing `/xroad/**` externally: this is a security misconfiguration,
   treat it as higher priority than the immediate availability complaint — fix the ingress/network policy
   so only the Security Server can reach port 8086, then re-verify the original symptom.

## Escalation
- Ops engineer checks general Ruuter health first (shared with the rest of the public gate API).
- Escalate to gate maintainers for anything in the `xroad/` DSL itself (member-code resolution, subset
  enforcement logic).
- Escalate to the platform/infra owner immediately if `/xroad/**` turns out to be externally reachable.
- Escalate to the specific authority's technical contact for Security-Server-side issues (their
  `X-Road-Id` format, their client configuration) — this gate's logs can tell you *that* the request was
  malformed, not *why* their Security Server sent it that way.

## Dashboards / queries
- Not yet available — Ruuter doesn't expose a Prometheus `/metrics` endpoint today (only
  `edelivery`/`xml-mapper` do). `GET /xroad/health/ready` and `docker compose logs ruuter` are the only
  current signal for this surface.
