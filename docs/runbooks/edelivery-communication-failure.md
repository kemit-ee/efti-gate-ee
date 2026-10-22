# eDelivery (AS4) communication failure

## Symptoms
- A consignment upload, search, or follow-up to/from a specific peer gate never completes or times out.
- `edelivery`'s outbound send fails (`EDeliveryClient.send` throws `IOException: eDelivery request
  failed...`), or the peer returns a SOAP fault.
- An inbound AS4 message fails signature/digest verification and is rejected.

## Diagnosis
1. Check the **"eDelivery messages (inbound / outbound)" stat panel** on the Grafana dashboard
   (`http://localhost:3000/d/efti-gate-overview`) and `edelivery_messages_outbound_total_failure` /
   `edelivery_messages_outbound_total_success` directly in Prometheus
   (`code/edelivery/src/edelivery/EDeliveryMetrics.kt`) to see whether outbound sends are failing at all,
   and the **"ONLINE gates/platforms known to edelivery"** panel
   (`edelivery_parties_online_total`) to see whether the registry even has a route to the peer.
2. Confirm the peer is `ONLINE` in our own registry (`registry/gates/<id>.yml` / served as
   `/gates/<id>.json`) — a `DISABLED` peer is excluded from `edelivery`'s AS4 party map entirely
   (`EDeliveryPartyRegistry`/`RegistryClient`, refreshed every `REGISTRY_REFRESH_SECONDS`, default 10s).
   If you just flipped a peer's status, allow one refresh interval before assuming it's broken.
3. `docker compose logs edelivery` — look for:
   - `SignatureValidator` failures on inbound messages — usually a cert mismatch (see
     [certificate-expiry.md](certificate-expiry.md)) or the peer using a different key than what's in
     our registry for them.
   - `UnsupportedOperationException: Unsupported message '<tag>'` — an inbound message type we don't
     have a handler for (`EftiMessageHandlers.rootTags`); check the tag against the FTI message list.
   - A timeout/connection-refused on send — the peer's `eDeliveryUrl` (from their registry entry) may be
     wrong, or the peer itself is down. `EDELIVERY_TIMEOUT_SECONDS` (default 60) gates how long we wait.
4. There is no AS4-level ping/heartbeat any more (removed per AGENTS.md) — "is the peer reachable" has to
   be inferred from a real message attempt failing, not a dedicated health check.

## Resolution
1. If the peer is `DISABLED` in our registry by mistake: fix `registry/<type>/<id>.yml`, commit, and
   `docker compose up --build registry` (takes effect for `edelivery` within `REGISTRY_REFRESH_SECONDS`).
2. If it's a signature/cert mismatch: see [certificate-expiry.md](certificate-expiry.md) — coordinate the
   cert exchange with the peer rather than retrying.
3. If it's a transient network/timeout issue on one peer: no action needed beyond waiting/retrying — AS4
   delivery already has `sendWithRetry`; escalate only if failures persist past a few retries.
4. If it's an unsupported message type: this is a protocol/version mismatch with the peer — escalate to
   gate maintainers rather than trying to work around it operationally.

## Escalation
- Ops engineer checks registry status and recent logs first.
- Escalate to gate maintainers for anything pointing at a protocol/signature/cert bug on our side.
- Escalate to the peer gate operator (via the contact list gate maintainers hold) for anything that's
  clearly on their end (their URL unreachable, their cert expired, their message malformed).

## Dashboards / queries
- Grafana: "eFTI Gate overview" dashboard panels "eDelivery messages (inbound / outbound)" and "ONLINE
  gates/platforms known to edelivery".
- Prometheus queries: `edelivery_messages_outbound_total_failure`, `edelivery_parties_online_total`.
- Missing: per-peer breakdown (these metrics aren't labeled by party id yet) and any signal from the
  peer's side, which this gate obviously can't see.
