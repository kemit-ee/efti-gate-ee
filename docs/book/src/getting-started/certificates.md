# Create and exchange gate certificates

Gates trust each other through **AS4 certificates**. Every gate has:

- **its own key pair** in a PKCS#12 keystore, `own.p12`. Only this gate has it. edelivery loads it
  from `$KEYSTORE_DIR/own.p12`, with the password `KEYSTORE_PASSWORD` and the alias equal to the
  gate id (`OWN_GATE_ID`);
- **the public certificate of every peer gate**, in its registry: the `eDeliveryCert` of each
  gate entry.

The sender signs a message with its own key and encrypts it for the receiver's registered
certificate. The receiver decrypts it with its own key and checks the signature against the
sender's registered certificate. So "exchanging certificates" means **each gate's public certificate
goes into every other gate's registry**.

> The local gates run edelivery in `TURVIS_INTEGRATION_MODE=legacy`: edelivery holds the key itself.
> With Turvis the key moves into the Turvis service. The registry side stays the same.

The repository ships working keys for both local gates, so the network runs without this chapter.
Follow it to give a gate its own key, to add a gate, or to see what a broken exchange looks like
before you meet one for real.

## Where the local keys and certificates live

| Gate | Keystore folder (`GATE_CERTS_DIR`) | Registry entry | Subject, alias | Password |
|---|---|---|---|---|
| G1 | `local/certs/g1/` | `local/network/G1.yml` | `CN=G1`, `G1` | `changeit` |
| G2 | `local/certs/g2/` | `local/network/G2.yml` | `CN=G2`, `G2` | `changeit` |

- **The keystore folder** holds `own.key` (private key), `own.crt` (public certificate) and `own.p12`
  (both, for edelivery). Compose mounts it read-only into the gate's edelivery as `/app/certs`.
  `.dockerignore` keeps `local/certs/` out of every image.
- **`local/network/`** holds the registry entry of every gate of the local network: id, country, AS4
  address and public certificate. Every gate's `registry-build` mounts **all** of these files
  as `gates/` (`local/compose.yaml`), so every gate knows every other gate's certificate. That is the
  exchange.

## The `keygen` service

One Compose service does both halves for a gate (in `local/`):

```bash
docker compose --env-file gates/g2.env run --rm keygen
```

1. If `GATE_CERTS_DIR` has no `own.p12`, it creates a new key pair and keystore. An existing key is
   kept.
2. It writes the gate's registry entry `network/<GATE_ID>.yml` from `own.crt`.

It runs in the `postgres:18` image, which ships the `openssl` CLI and is already pulled for the
databases. The commands are the same as `code/certs/generate.sh`, with a 3-year validity:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out own.key
openssl req -new -x509 -key own.key -out own.crt -days 1095 -subj "/CN=$GATE_ID"
openssl pkcs12 -export -out own.p12 -inkey own.key -in own.crt -name "$GATE_ID" -passout pass:changeit
```

Rules edelivery and `registry-build` enforce:

- **The alias (`-name`) must be exactly the gate id.** Otherwise edelivery doesn't find its key.
- **The keystore password must match `KEYSTORE_PASSWORD`** (default `changeit`).
- **The certificate must be valid now.** `registry-build` *fails* on an expired or not-yet-valid
  `eDeliveryCert`, and edelivery refuses an invalid peer certificate.
- **edelivery runs as a non-root user**, so the files are made readable (`644`). The service gives
  them to `HOST_UID:HOST_GID` (default `1000:1000`); set both in the shell if your user id differs.

On a gate that already has a key it changes nothing but the entry:

```text
kept the existing key pair of G1
wrote network/G1.yml; run 'up -d --wait' on every gate to share it
subject=CN=G1
serial=2993739BC3DB6F0D77B4BED6DEFBAE816EF91FF6
notAfter=Nov  3 09:27:23 2029 GMT
```

## Rotate a gate's key: a worked example

This replaces G2's key and shows what happens between the two halves of the exchange. All
commands in `local/`.

### Step 1: create the new key

Remove the old keystore, so `keygen` creates a new one:

```bash
rm certs/g2/own.key certs/g2/own.crt certs/g2/own.p12
docker compose --env-file gates/g2.env run --rm keygen
```

```text
created a new key pair for G2 in GATE_CERTS_DIR
wrote network/G2.yml; run 'up -d --wait' on every gate to share it
subject=CN=G2
serial=2DFFC1974C2AF30CE96FAE716AD3B2ADF40FE11B
notAfter=Nov  3 09:33:45 2029 GMT
```

### Step 2: update only G2, and watch AS4 break

```bash
docker compose --env-file gates/g2.env up -d --wait
docker compose --env-file gates/g2.env up -d --wait --force-recreate edelivery
```

The first command re-runs G2's `registry-build` (it runs on every `up`, so a change in
`network/` is picked up; no image is rebuilt). The second restarts G2's edelivery onto the new key: the keystore is a
mounted folder, so Compose doesn't see the change by itself. `--wait` matters: an edelivery that is
still starting answers nothing, and a test run started then fails.

G2 now signs and decrypts with the new key, but G1's registry still holds the old G2
certificate:

| Registry | Serial of G2's certificate |
|---|---|
| `local/certs/g2/own.crt` (new) | `2DFFC197…` |
| G2's registry | `2DFFC197…` |
| G1's registry | `7728B9C6…` (old) |

AS4 breaks in **both** directions (send API, FTI019 sample):

```bash
curl -s -X POST http://127.0.0.1:27081/api/v1/send/G2 \
  -H 'Content-Type: text/xml' \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)" \
  --data-binary @../code/xml-mapper/xsd/FTI019/sample.xml
```

| Direction | Answer | Why | Receiver's log |
|---|---|---|---|
| G1 → G2 | `500 … EBMS:0004 - XML_PARSE_FAIL` | G1 encrypts for the *old* G2 certificate; G2 can't decrypt it | `reason=XML_PARSE_FAIL` |
| G2 → G1 | `500 … EBMS:0101 - SIG_CRYPTO_FAIL` | G2 signs with the *new* key; G1 checks it against the old certificate | `reason=SIG_CRYPTO_FAIL` |

```bash
docker logs --since 5m efti-g1-edelivery 2>&1 | grep -oE 'reason=[A-Z_]+' | sort | uniq -c
```

`XML_PARSE_FAIL` looks like a malformed message, but here it means the payload couldn't be
decrypted. When you see it right after a key change, suspect the certificates first.

### Step 3: complete the exchange

Give the other gate the new certificate. A plain `up` is enough; it re-runs G1's `registry-build` from
`network/` and restarts what depends on it:

```bash
docker compose --env-file gates/g1.env up -d --wait
```

G1's registry now serves serial `2DFFC197…` too, and AS4 works again both ways (`200`, an
`FTI021SearchIdentifierResponse`). With more gates, run the same `up` on every gate.

### Step 4: verify

1. **edelivery logs the key it loaded at startup.** The serial is printed in decimal; compare it with
   the certificate's (hexadecimal):

   ```bash
   docker logs efti-g2-edelivery 2>&1 | grep -oE 'SerialNumber: [0-9]+' | tail -1
   openssl x509 -in certs/g2/own.crt -noout -serial
   ```

   Convert one to the other, e.g. `echo "ibase=16; 2DFFC1974C2AF30CE96FAE716AD3B2ADF40FE11B" | bc`.

2. **Every gate's registry serves the new certificate.** The registry serves ids in lower case:

   ```bash
   curl -s http://127.0.0.1:27080/gates/g2.json
   ```

3. **Both gates pass their tests** ([chapter 6](./newman.md)):

   ```bash
   docker compose --env-file gates/g1.env run --rm newman
   docker compose --env-file gates/g2.env run --rm newman
   ```

   Folder **2. G2G -> peer gate** of each run is the AS4 round trip in that direction.

To go back to the committed key: `git checkout -- certs/g2 network/G2.yml`, then `up -d --wait` on
both gates and `up -d --wait --force-recreate edelivery` on G2.

This sequence was run for the book; after step 3 both gates passed 199 of 199 assertions.

## Exchanging certificates with a real gate

Two deployed gates follow the same model, but the exchange is a coordination between two
operators, not a Compose service. This is what the repository documents (see
[`registry/README.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/registry/README.md) and the
runbook
[`docs/runbooks/certificate-expiry.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/certificate-expiry.md)):

1. **Create your gate's key pair and `own.p12`** with alias = `OWN_GATE_ID`. In production the
   keystore is a secret (SSM → ExternalSecret), never a file in the repository.
2. **Send only `own.crt`**, the public certificate, to the peer operator, out of band. Receive theirs.
3. **Add the peer to `registry/gates/<PEER-ID>.yml`:**

   ```yaml
   id: G2                     # equals the file name
   countryCode: LV
   eDeliveryUrl: https://<peer AS4 access point>/services/msh
   eDeliveryCert: |
     -----BEGIN CERTIFICATE-----
     ...
     -----END CERTIFICATE-----
   tlsCert: |                    # optional: the peer's TLS certificate, if its HTTPS endpoint
     -----BEGIN CERTIFICATE-----  #   is not signed by a CA the JVM already trusts
     ...
     -----END CERTIFICATE-----
   status: ONLINE
   ```

   A gate without `eDeliveryCert` is left out of edelivery's party map. With Turvis,
   `allowedChainAnchors` switches that gate from pinning (the signer certificate must equal
   `eDeliveryCert`) to chain-of-trust against `registry/trust-anchors/<alias>.pem`.
4. **Keep your own entry current.** Update `eDeliveryCert` in `registry/gates/<OWN_GATE_ID>.yml`
   whenever your key changes.
5. **Re-run `registry-build`** (`docker compose up -d registry-build`; in Kubernetes the init container
   re-runs on a pod restart). It validates every entry and fails on a bad or
   expired certificate. Then restart edelivery, as the runbook does.
6. **Verify** with the serial-number check above. Then send one real request each way, e.g. an
   authority search that fans out.

**Rotating a key** works like steps 2 and 3 above. There is an unavoidable gap: until a peer has
your new certificate, messages between you fail exactly as in the table in step 2. So agree a
cutover time with every peer operator and switch together. The full procedure is in the runbook.

Next: [Verify with Postman](./postman.md).
