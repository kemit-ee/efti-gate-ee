# AS4 inbound — `POST /services/msh`

The only public gate-to-gate route: a peer gate (for example Fintraffic, `EU-FI`) posts an AS4
message here. The Ruuter edge proxy (`DSL/Ruuter/services/POST/msh.yml`) checks the request and
forwards it to `edelivery`, which looks the sender up in the registry, decrypts, verifies the
signature and dispatches the payload. The procedure behind these cases is
[external-gate-interop-test.md](../runbooks/external-gate-interop-test.md).

```bash
BASE=https://eftiprelive.kemitaws.ee
```

Sources: **observed (prelive, 2026-11-04)**. The `prelive` registry holds the gates `EU-EE` (this
gate), `EU-FI` and `EU-TEST` (a test peer with its own key, `eDeliveryUrl` `https://eu-test.invalid/…`, so
nothing can be sent back to it). Test data for the identifier search: [data/README.md](data/README.md).

---

## 1. Edge (no peer key needed)

### 1.1 GET → 405

`curl -i $BASE/services/msh` — status **405**. A **200** `eDelivery MSH endpoint is up` would mean the
request bypassed Ruuter (see the runbook, step 2).

### 1.2 POST without `Content-Type` → 415

`curl -i -X POST $BASE/services/msh` — status **415**, `application/problem+json`.
A **500** `application/soap+xml` fault here also means the edge is bypassed.

---

## 2. Messages from a peer (need the peer's own keystore)

A valid message is signed with the sender's key and encrypted to the **public certificate of this
gate's keystore** (`EU-EE`), so it cannot be written by hand. The gate's own generator
(`EDeliveryMessageGenerator.requestMessage`, run with the sender's keystore) produces it. The payload
used below is `code/xml-mapper/xsd/FTI019/sample.xml` (an identifier search). Post it as

```bash
curl -i -X POST $BASE/services/msh \
  -H 'Content-Type: multipart/related; type="application/soap+xml"; boundary="<boundary of the file>"' \
  --data-binary @message.bin
```

In `legacy` mode the gate does not de-duplicate, so the same file can be posted again; in `turvis` mode a repeated `MessageId` is answered with the stored receipt.

### 2.1 sender not in the registry → 500 `OTHER`

- Status **500**, SOAP fault `OTHER`, `Failed to process eDelivery message`.
- Also the answer when the sender's gate is `DISABLED` or has no `eDeliveryCert`.
- Source: observed (prelive, 2026-11-04, `EU-TEST` before it was in the registry).

### 2.2 known sender, encrypted to another certificate → fault

- A SOAP fault (`XML_PARSE_FAIL` when the key identifier / serial in the message is not the
  keystore's, `PAYLOAD_DECRYPT_FAIL` when it is but the key does not decrypt).
- Source: derived from `EDeliveryRoutes.decryptPayload`; the certificate in the registry
  (`registry/gates/EU-EE.yml`) must be the keystore's, or peers that use it get this answer.

### 2.3 valid message → 200 with a signed receipt

- Status **200**, `application/soap+xml`, body an `env:Envelope` whose `eb:SignalMessage` has a
  `RefToMessageId` equal to the request's `MessageId` and an `eb:Receipt` with three
  `ds:Reference` entries (`#MSG-ID`, `#BODY-ID`, `cid:message`); the header carries the gate's
  `ds:Signature` (`KeyIdentifier` = the SKI of the `EU-EE` certificate).
- The identifier search itself is answered **asynchronously**: `edelivery` logs
  `Handling 'FTI019SearchIdentifierRequest' from <sender>:…` and sends the FTI021 reply to the
  sender's `eDeliveryUrl`. The HTTP 200 only proves the message was accepted.
- Source: observed (prelive, 2026-11-04).

## What to give a partner

The public certificate that matches the deployed keystore, the URL `https://eftiprelive.kemitaws.ee/services/msh`,
and the gate id `EU-EE`. The certificate must be the one in `registry/gates/EU-EE.yml` of the
environment's registry image. In that file `eDeliveryUrl` is the internal
`http://edelivery:8081/services/msh`; the public URL is only given to the partner.
