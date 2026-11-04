# Interoperability test with an external gate

Use this to check that another gate can reach this gate over AS4 (inbound) and that this gate can
reach it (outbound), before or after a peer is onboarded. A local variant with a tunnel is at the end.

## Symptoms
- A peer operator reports that messages to this gate fail, or a new peer is about to be onboarded and
  the connection has not been proven yet.
- After a certificate, URL or ingress change, nobody knows whether AS4 still works end to end.

## Diagnosis

### 1. Prerequisites (this gate)
- `OWN_GATE_ID` is the AS4 PartyId the peer will use. The keystore `own.p12` has the same alias and is
  mounted at `/app/certs/own.p12` (`docs/specs/deploy/resource-and-secrets-requirements.md` §3.5). It is
  not the dev keystore, and its certificate is not expired.
- `TURVIS_INTEGRATION_MODE=legacy`. `shadow` and `turvis` have not been proven against a real peer.
- `registry/gates/<OWN_GATE_ID>.yml` carries the real `eDeliveryUrl` (`https://<domain>/services/msh`)
  and the public certificate.
- `registry/gates/<peer>.yml` exists: file name equals `id`, values are strings, `status: ONLINE`,
  `eDeliveryUrl`, `eDeliveryCert` (PEM literal block), `countryCode`. A peer without `eDeliveryCert` is
  not an AS4 party.
- `python3 scripts/registry-to-json.py` passes, the registry image is rebuilt
  (`docker compose up --build registry`), and `docker compose logs edelivery` shows
  `Fetched gates: [...]` containing the peer (refresh takes up to `REGISTRY_REFRESH_SECONDS`, 10 s).
- Exchanged with the peer: both gate ids, both `eDeliveryUrl`s, both public certificates, both egress IPs
  if either side filters by IP.

### 2. Exposure
Only one route is public: `POST /services/msh`, HTTPS 443 to Ruuter 8086. Nothing else of Ruuter
(`/admin`, `/efti`, `/xroad`, `/auth`, `/ops`) or any other service may be reachable from outside. The
request must be `Content-Type: multipart/related; type="application/soap+xml"; boundary=...`, at most
10 MiB.

From outside the network:

```bash
curl -i -X POST https://<domain>/services/msh      # expect 415 (Content-Type is required)
curl -i https://<domain>/services/msh              # expect 405 or 403, never 200
curl -i -X POST https://<domain>/admin/v1/gates    # expect 404 or 403
```

A timeout, a TLS error or a 404 on `/services/msh` means the ingress is wrong; nothing below will work.

These codes also show which hop answers. The Ruuter edge proxy (`DSL/Ruuter/services/POST/msh.yml`)
answers a missing Content-Type with **415** `application/problem+json` and a GET with **405**. If a
missing Content-Type gives a **500** `application/soap+xml` fault, or a GET gives **200**
`eDelivery MSH endpoint is up`, the request went straight to `edelivery` and bypassed Ruuter's
Content-Type check, 10 MiB cap and in-flight cap. Messages still arrive, so inbound tests pass, but the
edge is not what the architecture says. In the devops repo the cause is the UI nginx in
`charts/ui/values.yaml` (`location /services/msh { proxy_pass http://edelivery:8081; }`, present since
the first bootstrap, before `msh.yml` existed). Point it at `http://ruuter:8086` and add
`client_max_body_size 10m;` and `proxy_request_buffering off;` there (nginx defaults to 1 MiB and would
answer larger AS4 messages with 413).

### 3. Inbound: peer to this gate
Ask the peer to send a message (for example an identifier search), then:

```bash
docker compose logs -f edelivery | grep -E "services/msh|Handling|Unknown receiver|WARN|ERROR"
```

Expected: `Handling 'FTI019SearchIdentifierRequest' from <peer>:<conversation>:<own>` and
`POST /services/msh: 200`. Ruuter logs `POST /services/msh 200`.

### 4. Outbound: this gate to peer
Start a search that no local row matches and poll for the peer's answer. `X-Request-ID` must be a UUID,
and the body must use a criteria field such as `mainTransportId`:

```bash
R=$(uuidgen | tr A-Z a-z)
H='X-Internal-Service-Token: <INTERNAL_SERVICE_TOKEN>'
curl -s -i -H "$H" -H 'Content-Type: application/json' -H "X-Request-ID: $R" -X POST \
  -d '{"mainTransportId":{"operator":"EQ","id":"XYZ"}}' http://<ruuter>:8086/efti/api/v1/authority/search
# 200, [] and x-poll-more: true while the peers are queried in the background
curl -s -i -H "$H" -H 'Content-Type: application/json' -H "X-Request-ID: $R" -H 'X-Poll: true' -X POST \
  -d '{}' http://<ruuter>:8086/efti/api/v1/authority/search
# wait until x-poll-more: false (up to about 30 s)
```

Expected in `edelivery` logs: `Sending message to <peer eDeliveryUrl>`, then
`POST <peer eDeliveryUrl> in N ms - 200`, then (after the peer replies on our `/services/msh`)
`Handling 'FTI021SearchIdentifierResponse' from <peer>:...`. The peer's reply is a separate inbound
message, so it needs the inbound path (step 2) too.

### Reading failures

| Symptom | Likely cause |
|---|---|
| Peer times out | ingress, firewall, DNS or TLS on this side |
| 500 SOAP fault for a request without Content-Type, or 200 on GET | ingress bypasses Ruuter (see step 2) |
| 415 / 400 before `edelivery` logs anything | the peer does not send `multipart/related` with `application/soap+xml` and a boundary |
| `Unknown receiver: X` | the receiver id in the message is not `OWN_GATE_ID` |
| failure at party lookup | the peer is missing from the registry image, is `DISABLED`, or has no `eDeliveryCert` |
| `SIG_CRYPTO_FAIL`, `PAYLOAD_DECRYPT_FAIL` | wrong, rotated or expired certificate on either side, or a registry image that was not rebuilt |
| `XML_PARSE_FAIL` | malformed envelope, or an attachment that is not gzip |
| `UNSUPPORTED_MESSAGE`, `Unsupported message '<tag>'` | unknown payload root element; protocol mismatch, escalate |
| outbound `request failed ... EBMS:nnnn` | the peer rejected our message; compare `conversationId` in the peer's log |
| search stays `x-poll-more: true` | `xml-mapper` is not running, or the peer never answered on our `/services/msh` |

Inbound errors log only `audit_ref`, a body fingerprint and sizes, never the message body. Use the
`conversationId` to correlate with the peer's logs.

## Resolution
1. Fix the first failing step above, then repeat that step before moving on.
2. After any registry change: `docker compose up --build registry`, wait one refresh interval.
3. For certificate problems follow [certificate-expiry.md](certificate-expiry.md); for runtime failures
   follow [edelivery-communication-failure.md](edelivery-communication-failure.md).
4. When the test is done, remove any temporary ingress rule or tunnel, and record the peer id, URL and
   certificate expiry date.

## Escalation
- Cannot be reached from outside although the ingress is correct: network or platform team.
- Peer logs show a fault this gate did not produce, or signature failures after both certificates were
  verified: gate maintainers, with the `conversationId` and the `audit_ref` from `edelivery`.

## Dashboards / queries
- Prometheus: `edelivery_messages_inbound_total_*`, `edelivery_messages_outbound_total_success` and
  `_failure`, `edelivery_parties_online_total`.

## Local test with a tunnel
This proves the whole path (internet, TLS, ingress filter, `/services/msh`, decrypt, verify, handler,
reply) with the dev gate `EU-MOCK` standing in for the peer. It does not prove that a real peer accepts
our messages or that we accept theirs.

1. Start the stack. If ports collide with another project, remap them:
   `EFTI_RUUTER_PORT=8186 EFTI_RESQL_PORT=8190 EFTI_DB_PORT=54331 EFTI_REGISTRY_PORT=8180 docker compose up -d --build ruuter edelivery xml-mapper`.
   If the database volume is older than a changed changeset, Liquibase fails with a checksum error; the
   local fix is `docker compose down -v` (this deletes local data).
2. Put a filter in front of Ruuter so that the tunnel exposes only `POST /services/msh`. Ruuter also
   serves `/admin`, `/xroad`, `/auth` and `/ops` on the same port:

   ```nginx
   server {
     listen 8080;
     client_max_body_size 10m;
     proxy_request_buffering off;
     location = /services/msh {
       limit_except POST { deny all; }
       proxy_pass http://efti_ruuter:8086;
       proxy_http_version 1.1;
       proxy_set_header Host $host;
     }
     location / { return 404; }
   }
   ```

   ```bash
   docker run -d --name efti_edge_filter --network efti_gate_ee_default -p 127.0.0.1:8187:8080 \
     -v "$PWD/edge.conf:/etc/nginx/conf.d/default.conf:ro" nginx:alpine
   ```

3. Open the tunnel (no install, no account) and note the printed `https://<id>.lhr.life` URL:

   ```bash
   ssh -o ServerAliveInterval=30 -o ExitOnForwardFailure=yes -R 80:127.0.0.1:8187 nokey@localhost.run
   ```

   The URL changes on every start. Check from outside as in step 2 of the diagnosis.
4. Temporarily set `eDeliveryUrl` in `registry/gates/EU-MOCK.yml` to `<tunnel URL>/services/msh`, run
   `docker compose up -d --build registry` and wait 10 s. **Do not commit this change.**
5. Seed a row that only `EU-MOCK` owns (the fixture in `tests/authority/authority-api.http`, a
   `POST /efti/insert_consignment` to ReSql with `gateId: EU-MOCK`, `mainTransportId: MOCK-123`) and run
   the outbound search from step 4 of the diagnosis for `MOCK-123`. The poll returns that row with
   `gateId: EU-MOCK`, and the filter container logs `POST /services/msh ... 200`.
6. Clean up: stop the `ssh` process, `docker rm -f efti_edge_filter`, `git checkout registry/gates/EU-MOCK.yml`
   and rebuild the registry image.

For a real peer, give the peer the tunnel URL as your `eDeliveryUrl` (and set it in
`registry/gates/<OWN_GATE_ID>.yml`). Our `edelivery` keeps a peer's replies working only while the tunnel
is up, and the dev keystore and the shared dev certificate are not accepted by a real peer.
