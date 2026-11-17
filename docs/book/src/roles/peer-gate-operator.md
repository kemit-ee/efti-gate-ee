# Peer gate

> **Goal:** this gate and your gate trust each other's AS4 messages: each holds the other's public
> certificate, each can reach the other's message endpoint, and a request sent one way gets an
> answer.

## Your role

You operate another member state's eFTI gate. Gates forward each other's authority searches, dataset
requests and follow-ups over eDelivery AS4, so an authority in one country can reach a consignment
registered in another. A gate is always both sides: it sends when its own authority asks for foreign
data, and it answers when a peer asks for its data.

## Path

1. **Understand the transport and the security profile.** [Peer gates (AS4)](../api/gates/index.md)
   and [AS4 endpoint](../api/gates/as4-endpoint.md).
2. **Exchange certificates** ([below](#connect)) and read
   [Create and exchange gate certificates](../getting-started/certificates.md), which includes a
   worked rotation and shows what a broken exchange looks like.
3. **Implement the three message pairs.**

| Purpose | Request → answer | Page |
|---|---|---|
| Identifier search | FTI019 → FTI021 | [search-fti019](../api/gates/search-fti019.md) |
| Dataset | FTI009 → FTI010 | [dataset-fti009](../api/gates/dataset-fti009.md) |
| Follow-up | FTI025 → FTI030 | [follow-up-fti025](../api/gates/follow-up-fti025.md) |

4. **See the whole exchange with logs on both sides:**
   [Answer an incoming request](../use-cases/peer-gate-request.md).
5. **Handle errors.** [Error catalogue](../data/errors.md) and the AS4 fault buckets on
   [AS4 endpoint](../api/gates/as4-endpoint.md).

## Connect

1. **Exchange public certificates out of band.** Send `own.crt` (PEM) and your `eDeliveryUrl`; receive
   ours. Confirm each certificate's SHA-256 fingerprint over a second channel. **Never send
   `own.key` or the `.p12` keystore.**
2. **Each side registers the other.** We add your gate as `registry/gates/<your-id>.yml` with `id`,
   `countryCode`, `eDeliveryUrl`, `eDeliveryCert` and `status: ONLINE`; you do the same for us. See
   [Change the registry](./registry-changes.md#gates) for the file.
3. **Check the endpoint is reachable.** The message service answers `GET` with a liveness line:

   ```bash
   curl -s https://<peer>/services/msh
   # eDelivery MSH endpoint is up, please send POST requests
   ```

4. **Send one real request each way.** For example an authority search that has to fan out
   ([Search for consignments](../use-cases/authority-searches.md)). The send API returns the outcome
   and both gates log the exchange ([the use case](../use-cases/peer-gate-request.md) shows the lines).

## What to agree with us

| Topic | Agree |
|---|---|
| Gate ids and country codes | The `id` is the AS4 party id. It must match the file name and the keystore alias |
| Message URL | Your public `eDeliveryUrl`. A URL with no path gets `/services/msh` |
| TLS | If your endpoint is not signed by a CA the JVM trusts, we need your `tlsCert` |
| Key rotation | A cutover time. Until both sides have the new certificate, messages between us fail |
| Status changes | `DISABLED` in a registry is a full stop: no messages to or from that gate |
| Contact on a failure | A named technical contact on each side, and the timestamp and gate id to quote |

## What fails when

The AS4 reply is a fixed fault bucket, never the exception text. The common ones:

| Symptom | Likely cause |
|---|---|
| `XML_PARSE_FAIL` (`EBMS:0004`) | Malformed multipart or envelope, **or** the sender encrypted for a key identifier that is not ours, typically our old certificate after a rotation. It looks like a bad message and is not |
| `SIG_CRYPTO_FAIL` (`EBMS:0101`) | No signature, or it does not verify against the certificate we hold for the sender, typically the sender's new key before we have its certificate |
| `PAYLOAD_DECRYPT_FAIL` (`EBMS:0102`) | The key identifier is ours but the encrypted content does not decrypt with our key |
| `PAYLOAD_TOO_LARGE` (`EBMS:0003`) | The decompressed payload is over 10 MiB |
| Receipt returned, but no answer message follows | The answer is a new AS4 message sent in the background. The answering gate could not deliver it: check its log, the other side's `eDeliveryUrl` and certificate |

The full table is on [AS4 endpoint](../api/gates/as4-endpoint.md). The worked case of a rotation that
breaks in both directions is in [certificates](../getting-started/certificates.md).

See [Known issues](../reference/known-issues.md) and [Troubleshooting](../reference/troubleshooting.md)
for the local network, and the runbook
[`edelivery-communication-failure`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/edelivery-communication-failure.md).

## Production cryptography

The signing, verification and encryption inside this repository's `edelivery` are for evaluation
and testing. Production uses the separate Turvis component, which is opt-in. The registry side of
the exchange does not change. See [Decision maker](./decision-maker.md#production-readiness).
