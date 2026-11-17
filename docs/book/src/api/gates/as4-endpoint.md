# AS4 endpoint

The AS4 message service handler (MSH). Peer gates, and platforms that use eDelivery, POST their
signed and encrypted AS4 user messages here. It answers with a signed receipt or a SOAP fault.

There are two layers with the same path:

`POST /services/msh` · on ruuter (G1 :27086, G2 :28086) · no credentials, header guard only · source `DSL/Ruuter/services/POST/msh.yml`, `DSL/Ruuter/services/POST/.guard.yml`

`GET|POST /services/msh` · on edelivery (G1 :27081, G2 :28081) · no credentials, the AS4 signature authenticates the sender · source `code/edelivery/src/edelivery/EDeliveryRoutes.kt`

Ruuter's route is a pass-through proxy: it checks the headers and the body size, then streams the
bytes unchanged to `[#EDELIVERY_URL]/services/msh`, so the signed bytes are never re-serialised.
Everything about the AS4 content is decided by edelivery.

## Request

| Header | Required | Value |
|---|---|---|
| `Content-Type` | yes | `multipart/related; type="application/soap+xml"; boundary="<boundary>"`. Ruuter needs the media type `multipart/related`, and the strings `application/soap+xml` and `boundary=` somewhere in the value. |
| `Content-Encoding` | no | Only `identity` passes Ruuter. The payload's gzip is inside the encrypted attachment, not on HTTP. |
| `Content-Length` / `Transfer-Encoding` | yes | edelivery's own client sends `Transfer-encoding: chunked`. |

The body has two MIME parts, separated by the boundary, with CRLF line ends:

| Part | Headers edelivery sends | Content |
|---|---|---|
| 1 | `Content-Type: application/soap+xml`, `Content-Transfer-Encoding: binary` | The SOAP 1.2 envelope: `eb:Messaging/eb:UserMessage` and `wsse:Security` (`EncryptedKey`, `EncryptedData`, `Signature`). The SOAP body is empty. |
| 2 | `Content-Type: application/octet-stream`, `Content-Transfer-Encoding: binary`, `Content-Description: Attachment`, `Content-ID: <message>` | 12-byte IV + AES-GCM ciphertext of the gzip-compressed FTI XML. |

edelivery takes the first part as the envelope and the last part as the encrypted payload. If the
`EncryptedKey` has no `CipherValue`, a middle part is used as the encrypted key.

What edelivery reads from the envelope (`MessageHeader.kt`):

| Element | Use |
|---|---|
| `UserMessage/PartyInfo/From/PartyId` | The sender. Must be a party in the registry, its `eDeliveryCert` verifies the signature, and the answer goes to its `eDeliveryUrl`. |
| `UserMessage/PartyInfo/To/PartyId` | Should be the own gate id. A different value is only logged (`Unknown receiver`); in Turvis mode it is refused (`RECEIVER_MISMATCH`). |
| `UserMessage/MessageInfo/MessageId` | Copied into the receipt's `RefToMessageId`. |
| `UserMessage/CollaborationInfo/ConversationId` | Must be a UUID (legacy). Becomes the request id of the handler call and the `ConversationId` of the answer. In Turvis mode any string up to 255 characters is accepted; a non-UUID maps to a name-based UUID internally and is echoed on the answer. |
| `EncryptedKey/KeyInfo/SecurityTokenReference/KeyIdentifier` or `X509Data/X509IssuerSerial/X509SerialNumber` | Must name the own certificate (SKI or serial number). |
| `EncryptedKey/CipherValue` | The RSA-OAEP-wrapped AES key. |
| `EncryptedKey/EncryptionMethod/@Algorithm`, `EncryptedData/EncryptionMethod/@Algorithm` | Expected `xmlenc11#rsa-oaep` and `xmlenc11#aes128-gcm`; anything else is logged as a warning, decryption is still attempted with the same algorithms. |
| `Signature/SignedInfo/Reference` | Verified; the first three are repeated in the receipt. |
| `PartProperties/Property[@name=CompressionType]` | `application/gzip` → the decrypted payload is gunzipped (max 10 MiB after decompression). |

## Processing (legacy mode)

1. Ruuter's guard checks `Content-Type`; the route checks `Content-Encoding` and the body cap
   (10 MiB, `max_body_bytes: 10485760`).
2. edelivery splits the multipart body. Fewer than two parts, or a part of the wrong type → `400`.
3. It parses the envelope, looks up the sender in the party map, checks the key identifier,
   decrypts the AES key and the payload, and verifies the signature.
4. It reads the root element of the decrypted XML and picks the handler
   (`FTI009GetCmdsRequest`, `FTI019SearchIdentifierRequest`, `FTI025LodgeFollowUpCommRequest`,
   `FTI004UploadIdentifierRequest`, the three answer types, or `hello`). An unknown root → fault.
5. It answers `200` with a signed receipt.
6. Only then, in the background, it runs the handler. A request handler calls the gate's Ruuter
   ([Inbound handlers](./inbound-handlers.md)) and sends the answer as a new AS4 message to the
   sender. An answer handler (`FTI010`, `FTI021`, `FTI029`, `FTI030`) hands the XML to the waiting
   [send](./send-api.md) call.

A failure in step 6 is not reported to the peer: the receipt is already sent. edelivery logs
`handler-dispatch failure: audit_ref=… reason=…`, counts it in
`edelivery_async_handler_failures_total_<reason>` and writes an `as4.handler_failed` row to
`audit_log`. The peer's waiting caller then times out.

## Responses

### Ruuter edge proxy

| Status | When | Body |
|---|---|---|
| `405` | Any method other than `POST` | `{"allow":["POST"],"error":"Method Not Allowed"}` |
| `415` | `Content-Type` missing or empty | problem+json `UNSUPPORTED_MEDIA_TYPE`, "Content-Type is required. …" |
| `415` | Media type is not `multipart/related` | problem+json `UNSUPPORTED_MEDIA_TYPE`, "Content-Type must be multipart/related. …" |
| `400` | `multipart/related` without `application/soap+xml` or without `boundary=` | problem+json `BAD_REQUEST_GENERAL` |
| `415` | `Content-Encoding` other than `identity` | `{"error":"proxy_unsupported_encoding",…}` |
| `413` | Declared `Content-Length` over 10485760 | `{"error":"proxy_body_too_large",…}` |
| any | Otherwise | edelivery's answer, passed through |

### edelivery

| Status | When | Body |
|---|---|---|
| `200` | `GET` | `eDelivery MSH endpoint is up, please send POST requests` (`text/xml`) |
| `200` | Message accepted (also `hello` and the answer types) | Signed receipt, `application/soap+xml` |
| `400` | Multipart body unreadable, fewer than two parts, wrong part types | SOAP fault `XML_PARSE_FAIL` |
| `413` | Body over `BODY_LIMIT_MB` (10 MiB) | klite JSON error `Maximum request body size is 10485760 bytes, …` |
| `500` | Any other failure before the receipt | SOAP fault with the reason below |

### SOAP faults

edelivery never sends the exception text to the peer. It maps the exception to a `FaultReason`
(`SoapFaultReasons.kt`) and writes a fixed text. In legacy mode these reasons can occur:

| Reason | ebMS code | Category / shortDescription | Detail text | Triggered by (legacy path) |
|---|---|---|---|---|
| `XML_PARSE_FAIL` | `EBMS:0004` | CONTENT / InvalidHeader | Malformed XML | Bad multipart, unparseable envelope, no key identifier or one that is not this gate's, gzip error, other parser errors on the header |
| `SIG_CRYPTO_FAIL` | `EBMS:0101` | PROCESSING / SignatureFailure | Signature invalid | No signature, signature does not verify, wrong C14N/signature/digest algorithm, sender certificate expired |
| `PAYLOAD_DECRYPT_FAIL` | `EBMS:0102` | PROCESSING / DecryptionFailure | Payload decryption failed | RSA or AES-GCM decryption fails (e.g. encrypted for another key) |
| `PAYLOAD_TOO_LARGE` | `EBMS:0003` | CONTENT / ValueInconsistent | Oversize payload | Decompressed payload over 10 MiB |
| `UNSUPPORTED_MESSAGE` | `EBMS:0002` | CONTENT / FeatureNotSupported | Unsupported message | Root element of the payload has no handler |
| `RECEIVER_MISMATCH` | `EBMS:0003` | CONTENT / ValueInconsistent | Message not addressed to this gate | Turvis mode: the signed `To/PartyId` is not this gate |
| `UNAVAILABLE` | `EBMS:0005` | COMMUNICATION / ConnectionFailure | Temporarily unable to process the message | I/O error or timeout before the receipt |
| `OTHER` | `EBMS:0004` | CONTENT / Other | Failed to process eDelivery message | Anything else, e.g. a sender that is not in the registry |

Only the Turvis path (`TURVIS_INTEGRATION_MODE=turvis`) produces these, with the same codes:
`XML_ATTACK_DETECTED` (0004), `SIG_MISSING`, `SIG_POLICY_FAIL`, `SIG_ALGO_UNSUPPORTED`,
`SIGNER_UNTRUSTED`, `SIGNER_IDENTITY_MISMATCH` (0101), `WRAPPED_KEY_MALFORMED`, `SKI_MISMATCH`,
`AES_UNWRAP_FAIL` (0102), `ALGO_UNSUPPORTED` (0003). In turvis mode, a Turvis outage or a
duplicate-store outage is answered `503` with an empty body and no fault, and a repeated `MessageId`
with other content gets `OTHER`.

The fault always has `env:Code` = `env:Receiver`, the reason in `env:Reason/env:Text` and in
`eb:Error/eb:ErrorDetail` as `<REASON>: <detail>`, and `origin="ebMS" severity="failure"`.

## Examples

### GET on edelivery

```bash
curl -s -i http://127.0.0.1:27081/services/msh
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 55

eDelivery MSH endpoint is up, please send POST requests
```

The same path on Ruuter only takes `POST`:

```bash
curl -s -i http://127.0.0.1:27086/services/msh
```

```http
HTTP/1.1 405 Method Not Allowed
content-type: application/json
allow: POST
content-length: 47

{"allow":["POST"],"error":"Method Not Allowed"}
```

### JSON instead of multipart → 415

```bash
curl -s -i -X POST http://127.0.0.1:27086/services/msh \
  -H 'Content-Type: application/json' -d '{}'
```

```http
HTTP/1.1 415 Unsupported Media Type
content-type: application/problem+json
content-length: 245

{"code":"UNSUPPORTED_MEDIA_TYPE","detail":"Content-Type must be multipart/related. The AS4 MSH endpoint does not accept other media types.","status":415,"title":"Unsupported Media Type","type":"https://api.efti.ee/errors/unsupported-media-type"}
```

With an empty `Content-Type` (`-H 'Content-Type:'`) the detail is
`Content-Type is required. The AS4 MSH endpoint accepts multipart/related only.`

Sent straight to edelivery (`:27081`), the same JSON body gets a SOAP fault instead:
`HTTP/1.1 400 Bad Request` with `XML_PARSE_FAIL: Malformed XML`.

### multipart/related without the SOAP marker → 400

```bash
curl -s -i -X POST http://127.0.0.1:27086/services/msh \
  -H 'Content-Type: multipart/related; boundary=b1' --data-binary 'x'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/problem+json
content-length: 225

{"code":"BAD_REQUEST_GENERAL","detail":"AS4 Content-Type must carry the root-part marker type=application/soap+xml and a boundary parameter.","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

### Malformed multipart body → 400 SOAP fault

The headers pass the guard, so Ruuter forwards the body and edelivery rejects it.

```bash
curl -s -i -X POST http://127.0.0.1:27086/services/msh \
  -H 'Content-Type: multipart/related; type="application/soap+xml"; boundary="b1"' \
  --data-binary 'not a multipart body'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/soap+xml; charset=UTF-8
content-length: 865
<env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope"><env:Header><eb:Messaging xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/" env:mustUnderstand="true"><eb:SignalMessage><eb:MessageInfo><eb:Timestamp>2026-11-03T16:03:28.896Z</eb:Timestamp><eb:MessageId>108a1df6-3e38-4776-b1e3-ba96a407baee@G1</eb:MessageId></eb:MessageInfo><eb:Error category="CONTENT" errorCode="EBMS:0004" origin="ebMS" severity="failure" shortDescription="InvalidHeader"><eb:Description xml:lang="en">InvalidHeader</eb:Description><eb:ErrorDetail>XML_PARSE_FAIL: Malformed XML</eb:ErrorDetail></eb:Error></eb:SignalMessage></eb:Messaging></env:Header><env:Body><env:Fault><env:Code><env:Value>env:Receiver</env:Value></env:Code><env:Reason><env:Text xml:lang="en">XML_PARSE_FAIL: Malformed XML</env:Text></env:Reason></env:Fault></env:Body></env:Envelope>
```

A well-formed multipart body with only one part gives the same `400`.

### Other faults (status and `eb:Error` only)

These were sent to `http://127.0.0.1:27086/services/msh` with hand-made two-part bodies. Only the
status line, `errorCode` and `ErrorDetail` of each answer are shown; the rest of the envelope is
the same as above.

| Body | Answer |
|---|---|
| Part 1 is a truncated envelope | `500` · `EBMS:0004` · `XML_PARSE_FAIL: Malformed XML` |
| Envelope with all header elements, `From` = `EU-XX` (not in the registry) | `500` · `EBMS:0004` · `OTHER: Failed to process eDelivery message` |
| `From` = `G2`, no `KeyIdentifier` | `500` · `EBMS:0004` · `XML_PARSE_FAIL: Malformed XML` |
| `From` = `G2`, `KeyIdentifier` of another certificate | `500` · `EBMS:0004` · `XML_PARSE_FAIL: Malformed XML` |
| `From` = `G2`, G1's own SKI, a random `CipherValue` | `500` · `EBMS:0102` · `PAYLOAD_DECRYPT_FAIL: Payload decryption failed` |
| A correct AS4 message from G1 whose payload root is `<foo/>` (sent with the [send API](./send-api.md)) | `500` · `EBMS:0002` · `UNSUPPORTED_MESSAGE` (as reported by the sender's log: `reason=EBMS:0002 - UNSUPPORTED_MESSAGE`) |

The receiving log line for each of them looks like this (G2, the `<foo/>` case):

```text
error edelivery.EDeliveryRoutes Error when processing message: audit_ref=674c1237-de67-4efe-ba67-97f688178259 reason=UNSUPPORTED_MESSAGE body_sha256=efe59d4becdf779291f2fd2ecf915a0fa87f13534d0d9e439f2d2c4c661e626f body_size_bytes=7511 content_type=multipart/related; type="application/soap+xml"; boundary="----=_Part_2ee41e83-c34d-4c21-95c5-1d095902e921" multipart_part_count=2
```

### Body too large → 413

```bash
head -c 11000000 /dev/zero > big.bin
curl -s -i -X POST http://127.0.0.1:27086/services/msh \
  -H 'Content-Type: multipart/related; type="application/soap+xml"; boundary="b1"' \
  --data-binary @big.bin
```

```http
HTTP/1.1 413 Payload Too Large
content-type: application/json
content-length: 145

{"cap":10485760,"declared":11000000,"error":"proxy_body_too_large","message":"declared Content-Length 11000000 exceeds proxy route cap 10485760"}
```

edelivery itself (`:27081`) answers the same body with:

```http
HTTP/1.1 413 Request Entity Too Large
Content-type: application/json; charset=UTF-8

{"detail":"Maximum request body size is 10485760 bytes, received 11000000 bytes","instance":"/services/msh","message":"Maximum request body size is 10485760 bytes, received 11000000 bytes","status":413,"title":"Payload Too Large"}
```

### HTTP Content-Encoding → 415

```bash
printf -- '--b1\r\nContent-Type: application/soap+xml\r\n\r\n<x/>\r\n--b1--\r\n' > one.bin
curl -s -i -X POST http://127.0.0.1:27086/services/msh \
  -H 'Content-Type: multipart/related; type="application/soap+xml"; boundary="b1"' \
  -H 'Content-Encoding: gzip' --data-binary @one.bin
```

```http
HTTP/1.1 415 Unsupported Media Type
content-type: application/json
content-length: 144

{"allowed":["identity"],"error":"proxy_unsupported_encoding","message":"Content-Encoding 'gzip' not permitted; allowed: identity","seen":"gzip"}
```

### A real exchange on the wire

A real AS4 message cannot be typed by hand: it has to be encrypted for the receiver and signed by
the sender. The message below was produced by G1's edelivery for an FTI019 search
([send API](./send-api.md), `X-Request-Id: 7246eadc-25c8-41ea-bd45-8bf80c194d22`) and captured on
the network of G2's edelivery (a raw-socket capture in a throw-away container started with
`docker run --rm --net container:efti-g2-edelivery …`). Indentation was added for reading;
on the wire each envelope is one line. Long base64 values are cut with `…`.

HTTP request headers and MIME framing, as sent by the Java HTTP client:

```http
POST /services/msh HTTP/1.1
Connection: Upgrade, HTTP2-Settings
Host: g2-edelivery:8081
HTTP2-Settings: AAEAAEAAAAIAAAAAAAMAAAAAAAQBAAAAAAUAAEAAAAYABgAA
Transfer-encoding: chunked
Upgrade: h2c
User-Agent: Java-http-client/25.0.4.1
Content-Type: multipart/related; type="application/soap+xml"; boundary="----=_Part_2ee41e83-c34d-4c21-95c5-1d095902e921"

------=_Part_2ee41e83-c34d-4c21-95c5-1d095902e921
Content-Type: application/soap+xml
Content-Transfer-Encoding: binary

<env:Envelope …> (below)
------=_Part_2ee41e83-c34d-4c21-95c5-1d095902e921
Content-Type: application/octet-stream
Content-Transfer-Encoding: binary
Content-Description: Attachment
Content-ID: <message>

<517 bytes: IV + AES-GCM ciphertext>
------=_Part_2ee41e83-c34d-4c21-95c5-1d095902e921--
```

Part 1, the SOAP envelope:

```xml
<env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
  <env:Header>
    <eb:Messaging xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/" xmlns:env="http://www.w3.org/2003/05/soap-envelope" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd" wsu:Id="MSG-ID" env:mustUnderstand="true">
      <eb:UserMessage>
        <eb:MessageInfo>
          <eb:Timestamp>2026-11-03T16:05:15.671Z</eb:Timestamp>
          <eb:MessageId>7246eadc-25c8-41ea-bd45-8bf80c194d22@G1</eb:MessageId>
        </eb:MessageInfo>
        <eb:PartyInfo>
          <eb:From>
            <eb:PartyId type="urn:oasis:names:tc:ebcore:partyid-type:unregistered">G1</eb:PartyId>
            <eb:Role>http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/initiator</eb:Role>
          </eb:From>
          <eb:To>
            <eb:PartyId type="urn:oasis:names:tc:ebcore:partyid-type:unregistered">G2</eb:PartyId>
            <eb:Role>http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/responder</eb:Role>
          </eb:To>
        </eb:PartyInfo>
        <eb:CollaborationInfo>
          <eb:Service type="tc1">bdx:noprocess</eb:Service>
          <eb:Action>eftiGateAction</eb:Action>
          <eb:ConversationId>7246eadc-25c8-41ea-bd45-8bf80c194d22</eb:ConversationId>
        </eb:CollaborationInfo>
        <eb:MessageProperties>
          <eb:Property name="originalSender" type="urn:oasis:names:tc:ebcore:partyid-type:iso6523:actorid-upis">iso6523-actorid-upis::9915:G1</eb:Property>
          <eb:Property name="finalRecipient" type="urn:oasis:names:tc:ebcore:partyid-type:iso6523:actorid-upis">iso6523-actorid-upis::9915:G2</eb:Property>
        </eb:MessageProperties>
        <eb:PayloadInfo>
          <eb:PartInfo href="cid:message">
            <eb:PartProperties>
              <eb:Property name="MimeType">text/xml</eb:Property>
              <eb:Property name="CompressionType">application/gzip</eb:Property>
            </eb:PartProperties>
          </eb:PartInfo>
        </eb:PayloadInfo>
      </eb:UserMessage>
    </eb:Messaging>
    <wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd" env:mustUnderstand="true">
      <xenc:EncryptedKey xmlns:xenc="http://www.w3.org/2001/04/xmlenc#" Id="EK-ID">
        <xenc:EncryptionMethod Algorithm="http://www.w3.org/2009/xmlenc11#rsa-oaep">
          <ds:DigestMethod xmlns:ds="http://www.w3.org/2000/09/xmldsig#" Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
          <xenc11:MGF xmlns:xenc11="http://www.w3.org/2009/xmlenc11#" Algorithm="http://www.w3.org/2009/xmlenc11#mgf1sha256"/>
        </xenc:EncryptionMethod>
        <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
          <wsse:SecurityTokenReference>
            <wsse:KeyIdentifier EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary" ValueType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509SubjectKeyIdentifier">UgTJDgdD6kHSfKNf9wN1paW9yQA=</wsse:KeyIdentifier>
          </wsse:SecurityTokenReference>
        </ds:KeyInfo>
        <xenc:CipherData>
          <xenc:CipherValue>VG2+upP5QViZBRlshB+E…</xenc:CipherValue>
        </xenc:CipherData>
        <xenc:ReferenceList>
          <xenc:DataReference URI="#ED-ID"/>
        </xenc:ReferenceList>
      </xenc:EncryptedKey>
      <xenc:EncryptedData xmlns:xenc="http://www.w3.org/2001/04/xmlenc#" Id="ED-ID" MimeType="application/gzip" Type="http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Only">
        <xenc:EncryptionMethod Algorithm="http://www.w3.org/2009/xmlenc11#aes128-gcm"/>
        <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
          <wsse:SecurityTokenReference xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" wsse:TokenType="http://docs.oasis-open.org/wss/oasis-wss-soap-message-security-1.1#EncryptedKey">
            <wsse:Reference URI="#EK-ID"/>
          </wsse:SecurityTokenReference>
        </ds:KeyInfo>
        <xenc:CipherData>
          <xenc:CipherReference URI="cid:message">
            <xenc:Transforms>
              <ds:Transform xmlns:ds="http://www.w3.org/2000/09/xmldsig#" Algorithm="http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Ciphertext-Transform"/>
            </xenc:Transforms>
          </xenc:CipherReference>
        </xenc:CipherData>
      </xenc:EncryptedData>
      <ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#" Id="SIG-ID">
        <ds:SignedInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:env="http://www.w3.org/2003/05/soap-envelope">
          <ds:CanonicalizationMethod Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#">
            <ec:InclusiveNamespaces xmlns:ec="http://www.w3.org/2001/10/xml-exc-c14n#" PrefixList="env"/>
          </ds:CanonicalizationMethod>
          <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"/>
          <ds:Reference URI="#MSG-ID">
            <ds:Transforms>
              <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#">
                <ec:InclusiveNamespaces xmlns:ec="http://www.w3.org/2001/10/xml-exc-c14n#" PrefixList="env"/>
              </ds:Transform>
            </ds:Transforms>
            <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
            <ds:DigestValue>Zvwtv1sXKBTKf+Ct/Y08k9zKDwlH7T8HGNM7jQ2C95Q=</ds:DigestValue>
          </ds:Reference>
          <ds:Reference URI="#BODY-ID">
            <ds:Transforms>
              <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
            </ds:Transforms>
            <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
            <ds:DigestValue>3JMphhobpFaswT7+xah+redMYnlpFiKDYuqRjNKNxZk=</ds:DigestValue>
          </ds:Reference>
          <ds:Reference URI="cid:message">
            <ds:Transforms>
              <ds:Transform Algorithm="http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Signature-Transform"/>
            </ds:Transforms>
            <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
            <ds:DigestValue>OBLS+R0dQAhSaDe6kNbXlE3z2i0/dggLPO4SR1ClaQo=</ds:DigestValue>
          </ds:Reference>
        </ds:SignedInfo>
        <ds:SignatureValue>iN1ef9Um8xtzewPL8PVk…</ds:SignatureValue>
        <ds:KeyInfo Id="KI-4620d435-9abb-41c4-ad2e-1f60d8c71d16">
          <wsse:SecurityTokenReference>
            <wsse:KeyIdentifier EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary" ValueType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509SubjectKeyIdentifier">fB2nJdhdCiqCA+4K20s0uwMVc5c=</wsse:KeyIdentifier>
          </wsse:SecurityTokenReference>
        </ds:KeyInfo>
      </ds:Signature>
    </wsse:Security>
  </env:Header>
  <env:Body xmlns:env="http://www.w3.org/2003/05/soap-envelope" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd" wsu:Id="BODY-ID"/>
</env:Envelope>
```

`UgTJDgdD6kHSfKNf9wN1paW9yQA=` is the SKI of G2's certificate (the key the AES key is wrapped
for), `fB2nJdhdCiqCA+4K20s0uwMVc5c=` the SKI of G1's certificate (the signer). Decrypting the
captured attachment with G2's dev keystore gave a 256-bit AES key, a gzip stream whose SHA-256
is the `cid:message` digest above (`OBLS+R0d…`), and inside it the FTI019 XML.

G2's answer to this POST, the receipt:

```http
HTTP/1.1 200 OK
Date: Tue, 03 Nov 2026 16:05:15 GMT
Content-type: application/soap+xml; charset=UTF-8
Content-length: 4828
```

```xml
<env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
  <env:Header>
    <eb:Messaging xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/" xmlns:env="http://www.w3.org/2003/05/soap-envelope" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd" wsu:Id="MSG-ID" env:mustUnderstand="true">
      <eb:SignalMessage>
        <eb:MessageInfo>
          <eb:Timestamp>2026-11-03T16:05:15.686Z</eb:Timestamp>
          <eb:MessageId>670945c5-4560-4150-82e8-c630f303df3c@G2</eb:MessageId>
          <eb:RefToMessageId>7246eadc-25c8-41ea-bd45-8bf80c194d22@G1</eb:RefToMessageId>
        </eb:MessageInfo>
        <eb:Receipt>
          <ebbp:NonRepudiationInformation xmlns:ebbp="http://docs.oasis-open.org/ebxml-bp/ebbp-signals-2.0">
            <ebbp:MessagePartNRInformation>
              <ds:Reference xmlns:ds="http://www.w3.org/2000/09/xmldsig#" URI="#MSG-ID">
                <ds:Transforms>
                  <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#">
                    <ec:InclusiveNamespaces xmlns:ec="http://www.w3.org/2001/10/xml-exc-c14n#" PrefixList="env"/>
                  </ds:Transform>
                </ds:Transforms>
                <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
                <ds:DigestValue>Zvwtv1sXKBTKf+Ct/Y08k9zKDwlH7T8HGNM7jQ2C95Q=</ds:DigestValue>
              </ds:Reference>
            </ebbp:MessagePartNRInformation>
            <ebbp:MessagePartNRInformation>
              <ds:Reference xmlns:ds="http://www.w3.org/2000/09/xmldsig#" URI="#BODY-ID">
                <ds:Transforms>
                  <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
                </ds:Transforms>
                <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
                <ds:DigestValue>3JMphhobpFaswT7+xah+redMYnlpFiKDYuqRjNKNxZk=</ds:DigestValue>
              </ds:Reference>
            </ebbp:MessagePartNRInformation>
            <ebbp:MessagePartNRInformation>
              <ds:Reference xmlns:ds="http://www.w3.org/2000/09/xmldsig#" URI="cid:message">
                <ds:Transforms>
                  <ds:Transform Algorithm="http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Signature-Transform"/>
                </ds:Transforms>
                <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
                <ds:DigestValue>OBLS+R0dQAhSaDe6kNbXlE3z2i0/dggLPO4SR1ClaQo=</ds:DigestValue>
              </ds:Reference>
            </ebbp:MessagePartNRInformation>
          </ebbp:NonRepudiationInformation>
        </eb:Receipt>
      </eb:SignalMessage>
    </eb:Messaging>
    <wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd" env:mustUnderstand="true">
      <ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#" Id="SIG-ID">
        <ds:SignedInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:env="http://www.w3.org/2003/05/soap-envelope">
          <ds:CanonicalizationMethod Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#">
            <ec:InclusiveNamespaces xmlns:ec="http://www.w3.org/2001/10/xml-exc-c14n#" PrefixList="env"/>
          </ds:CanonicalizationMethod>
          <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"/>
          <ds:Reference URI="#MSG-ID">
            <ds:Transforms>
              <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#">
                <ec:InclusiveNamespaces xmlns:ec="http://www.w3.org/2001/10/xml-exc-c14n#" PrefixList="env"/>
              </ds:Transform>
            </ds:Transforms>
            <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
            <ds:DigestValue>JUxnzSJnGBisvmtDz8qVLU29GQunhHuuMp2/SfZPE1I=</ds:DigestValue>
          </ds:Reference>
          <ds:Reference URI="#BODY-ID">
            <ds:Transforms>
              <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
            </ds:Transforms>
            <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
            <ds:DigestValue>3JMphhobpFaswT7+xah+redMYnlpFiKDYuqRjNKNxZk=</ds:DigestValue>
          </ds:Reference>
        </ds:SignedInfo>
        <ds:SignatureValue>SAzebgAnvveVFiyKJzYv…</ds:SignatureValue>
        <ds:KeyInfo Id="KI-b1896222-c083-4ee1-94f0-f5c2c1af34b8">
          <wsse:SecurityTokenReference>
            <wsse:KeyIdentifier EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary" ValueType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509SubjectKeyIdentifier">UgTJDgdD6kHSfKNf9wN1paW9yQA=</wsse:KeyIdentifier>
          </wsse:SecurityTokenReference>
        </ds:KeyInfo>
      </ds:Signature>
    </wsse:Security>
  </env:Header>
  <env:Body xmlns:env="http://www.w3.org/2003/05/soap-envelope" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd" wsu:Id="BODY-ID"/>
</env:Envelope>
```

The same capture held the second exchange of the conversation: G2 POSTing the FTI021 answer
(8443 bytes, `ConversationId` `7246eadc-…`) to `g1-edelivery:8081/services/msh`, and G1's
4957-byte receipt.

## Notes

- The receipt repeats the received signature's first three references in a fixed order, with fixed
  transforms. A message whose signature has fewer than three references fails while the receipt is
  built and gets `OTHER`.
- In legacy mode the payload is decrypted before the signature is checked. A message encrypted for
  this gate but with a bad signature therefore fails as `SIG_CRYPTO_FAIL`, one with an unknown key
  identifier as `XML_PARSE_FAIL` (the Turvis path calls the latter `SKI_MISMATCH`, `EBMS:0102`).
- The sender of a message is taken from the envelope `From`, which is covered by the signature
  (`#MSG-ID`).
- `MessageId` duplicates are not detected in legacy mode: a repeated message is handled again.
- The `boundary` is chosen once per edelivery process and reused for every outbound message.
- Postman: **0. Health / edelivery MSH up**, and in folder **3** "AS4 edge /services/msh - JSON -> 415"
  and "… malformed multipart -> 400".
- Notes on docs: the description in `msh.yml` mentions an "in-flight concurrency cap", but the
  route declares only `max_body_bytes`, `allowed_encodings` and `request_timeout_ms`.
