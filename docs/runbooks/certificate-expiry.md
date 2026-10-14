# Certificate expiry (AS4 / TIM / TARA)

## Symptoms
- **AS4 (gate-to-gate):** `edelivery` fails to start, or a peer gate rejects/can't decrypt our
  messages; `edelivery` logs a missing `partyId: <OWN_GATE_ID>, KeyIdentifier/SKI: …, SerialNumber: …`
  line on startup, or a peer reports a signature/trust failure.
- **TIM/TARA (dev only):** `tim` fails to start because `tim-init` couldn't build the CA bundle from
  `tara-mock`'s self-signed cert, or TARA login fails with a TLS trust error.

## Diagnosis
1. **AS4 keystore (`own.p12`):**
   - Check the serial number: `openssl x509 -in own.crt -noout -serial` (hex) should match the decimal
     `SerialNumber` `edelivery` logs on startup (`docs/specs/deploy/resource-and-secrets-requirements.md`
     §3.5 "Kontroll pärast paigaldust"). Also check expiry directly: `openssl x509 -in own.crt -noout
     -enddate`.
   - If `edelivery` is in a restart loop with no `partyId: ...` log line at all, the cause is usually one
     of: the keystore file is missing at `$KEYSTORE_DIR/own.p12`, `KEYSTORE_PASSWORD` is wrong, or the
     keystore alias isn't exactly `OWN_GATE_ID`.
   - If `edelivery` starts fine but a **specific peer** rejects our messages, the peer's copy of our
     `eDeliveryCert` in their `registry/gates/<OWN_GATE_ID>.yml` is stale (they haven't picked up our
     latest cert) — this is a coordination problem, not a local one.
2. **TIM/TARA (dev):** check `docker compose logs tim-init` — it writes `tara-mock`'s self-signed cert
   into a bundle at `/tim-ca-bundle/ca-bundle.pem` before `tim` starts (`depends_on:
   service_completed_successfully`). A `tim-init` failure here blocks `tim` entirely, not just TARA
   login. In production, TIM talks to the real TARA, which has its own, centrally-managed certificate —
   this runbook's TIM/TARA section is dev-only.

## Resolution
1. **AS4 cert rotation** (planned, before expiry, or after a key leak) — coordinated with every peer
   gate operator, per `docs/specs/deploy/resource-and-secrets-requirements.md` §3.5 "Rotatsioon":
   1. Generate a new keypair and `own.p12` (alias = `OWN_GATE_ID`, key password = store password).
   2. Send the new `own.crt` to every peer gate operator and agree a cutover time.
   3. At the agreed time, simultaneously: update the keystore Secret (SSM → ExternalSecret in prod, or
      the `./code/certs` mount locally); update our own `registry/gates/<OWN_GATE_ID>.yml`
      `eDeliveryCert`; rebuild and deploy the `registry` image (`docker compose up --build registry`);
      restart `edelivery`.
   4. Every peer updates their own registry with our new cert.
   5. Messages to/from peers who haven't rotated their copy of our cert yet will fail until they do —
      this gap is unavoidable, especially on an emergency (key-leak) rotation.
2. **TIM/TARA (dev) cert issue** — `docker compose up --build tim-init tim` to regenerate the CA bundle
   and JWT signing key, then restart `tara-mock` and `tim`.

## Escalation
- Ops engineer runs the rotation mechanics (Secret update, image rebuild, restart).
- Escalate to gate maintainers to coordinate the peer-side cutover (they maintain the list of peer gate
  operator contacts).
- Escalate to the platform/infra owner for SSM/ExternalSecret access in production.

## Dashboards / queries
- Not available — there's no certificate-expiry metric exported (no scheduled check of `own.crt`'s
  `notAfter` against current time). Today this is a manual `openssl x509 -noout -enddate` check; adding
  it as a Prometheus metric is a reasonable next step once that's needed.
