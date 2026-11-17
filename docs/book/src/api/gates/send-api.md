# edelivery send API

The gate's own Ruuter uses this endpoint to send an FTI message to another gate (or to an
eDelivery platform) and to get the answer back as one synchronous call. edelivery wraps the XML in
an AS4 message, sends it, and waits until the peer's answer message with the same conversation id
arrives.

`POST /api/v1/send/{partyId}` · on edelivery (G1 :27081, G2 :28081) · `X-Internal-Service-Token` · source `code/edelivery/src/InternalRoutes.kt`, `code/edelivery/src/edelivery/EDeliveryClient.kt`

The token used below (`dev-internal-service-token-change-me`) is the public dev value from the
repository.

## Request

| Header | Required | Value |
|---|---|---|
| `X-Internal-Service-Token` | yes | Must equal `INTERNAL_SERVICE_TOKEN` (constant-time compare). |
| `X-Request-Id` | no | A UUID. Becomes the ebMS `ConversationId`, the `MessageId` (`<id>@<own gate>`) and the key the answer is matched on. Without it edelivery generates one. A value that is not a UUID → `400`. |
| `Content-Type` | yes | `text/xml` |

| Path parameter | Required | Type | Meaning |
|---|---|---|---|
| `partyId` | yes | string | Id of a gate or eDelivery platform in the registry party map (`G2`, `G1`, `mock-edelivery`). Case-insensitive. |

| Body | Required | Type | Meaning |
|---|---|---|---|
| FTI XML | yes | string | `FTI019SearchIdentifierRequest`, `FTI009GetCmdsRequest` or `FTI025LodgeFollowUpCommRequest` (to a gate). Not validated here: the root element is checked by the receiver. |

The gate builds these bodies with xml-mapper (`/api/v1/search/request-to-xml`,
`/api/v1/dataset/request-to-xml`, `/api/v1/followup/request-to-xml`, see
[xml-mapper](../internal/xml-mapper.md)). xml-mapper puts its `X-Request-Id` into
`RequestedSpecifiedQuery/ID`, so when the same id is used for both calls, the FTI query id and the
AS4 conversation id are the same.

## What happens

1. Look up `partyId` in the party map (`Unknown party` → `400`).
2. Register a waiter for the key `<partyId>:<X-Request-Id>:<own id>`.
3. gzip, encrypt and sign the XML ([security profile](./index.md#security-profile)), POST it to
   the party's `eDeliveryUrl`. On a connection error retry up to 5 times (waits 1, 2, 3, 4 s).
4. A non-2xx answer from the peer → `500` with the fault reason.
5. Otherwise wait up to `EDELIVERY_TIMEOUT_SECONDS` (60) for the answer message, polling the
   database for answers that landed on another node.
6. Return the answer XML as it came from the peer.

## Responses

| Status | When | Body |
|---|---|---|
| `200` | The peer's answer arrived | The answer XML (`FTI021…`, `FTI010…`, `FTI030…`), `Content-type: text/xml; charset=UTF-8` |
| `400` | `partyId` not in the party map (unknown, `DISABLED`, or a gate without `eDeliveryCert`) | `{"detail":"Unknown party: <id>",…,"status":400,"title":"Bad Request"}` |
| `400` | `X-Request-Id` is not a UUID | `{"detail":"Invalid UUID string: <value>",…}` |
| `401` | Token missing or wrong | `{"detail":"Missing or invalid X-Internal-Service-Token",…,"status":401}` |
| `500` | The peer answered the AS4 POST with a non-2xx status (SOAP fault) | `{"detail":"eDelivery request failed with status <n>: <EBMS code> - <REASON>",…}`. Only known reason names and the ebMS code are kept from the peer's answer; anything else reads `unrecognised`. |
| `500` | The peer could not be reached after 5 attempts | `All 5 attempts failed without hitting timeout` (not run locally: it needs a stopped peer) |
| `500` | The peer's certificate in the registry is not valid now | from `KeyManager.receiverCert` (not run locally: it needs key changes) |
| `504` | The peer accepted the request, but no answer came within 60 s | `{"detail":"No data received after 1m",…,"status":504,"title":"Gateway Timeout"}` |

A `504` means the request reached the peer and was receipted, but the peer's background handler
failed or its answer was rejected. The reason is only in the peer's log
(`handler-dispatch failure: audit_ref=… reason=…`) and in its `audit_log`.

## Examples

### FTI019 search, G1 → G2

Build the request with xml-mapper, then send it with the same request id:

```bash
RID=$(cat /proc/sys/kernel/random/uuid)
T='X-Internal-Service-Token: dev-internal-service-token-change-me'
curl -s http://127.0.0.1:27082/api/v1/search/request-to-xml -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: application/json' -d '{"mainTransportId":{"id":"G2-AB123","operator":"EQ"}}' > fti019.xml
curl -s -i http://127.0.0.1:27081/api/v1/send/G2 -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: text/xml' --data-binary @fti019.xml
```

```http
HTTP/1.1 200 OK
Date: Tue, 03 Nov 2026 15:59:57 GMT
Content-type: text/xml; charset=UTF-8
Content-length: 1310
<rsm:FTI021SearchIdentifierResponse xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI021SearchIdentifierResponse:1" xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34" xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34"><rsm:ExchangedDocumentContext>…</rsm:ExchangedDocumentContext><rsm:ExchangedDocument><ID schemeID="RFC 9562-4">1dc7d177-cd49-4168-acc9-d70eb741d9c9</ID><TypeCode>021</TypeCode><StatusCode>C</StatusCode><IssueDateTime><udt:DateTimeString format="207">20261103155957Z</udt:DateTimeString></IssueDateTime><RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">78ce1c60-05ca-4708-a022-7504a9f82a05</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G2</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">22222222-0000-4000-8000-000000000001</DatasetID><ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>G2-AB123</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
  </rsm:FTI021SearchIdentifierResponse>
```

The round trip took 66 ms (`POST /api/v1/send/G2: 200 in 66 ms` in G1's log). The log lines
of both gates are in [A peer gate asks this gate](../../use-cases/peer-gate-request.md).

### The other direction, G2 → G1

```bash
RID=$(cat /proc/sys/kernel/random/uuid)
curl -s http://127.0.0.1:28082/api/v1/search/request-to-xml -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: application/json' -d '{"mainTransportId":{"id":"IMO9321483","operator":"EQ"}}' > fti019-g2.xml
curl -s -i http://127.0.0.1:28081/api/v1/send/G1 -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: text/xml' --data-binary @fti019-g2.xml
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 1312
<rsm:FTI021SearchIdentifierResponse …>…<RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">77b0a949-741f-424d-a90a-7215bcb08cc5</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G1</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">11111111-0000-4000-8000-000000000003</DatasetID><ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>IMO9321483</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
  </rsm:FTI021SearchIdentifierResponse>
```

FTI009 and FTI025 were also sent G2 → G1 (dataset `11111111-0000-4000-8000-000000000003`, and a
follow-up to a new dataset id); both answered `200`.

### Unknown party → 400

```bash
curl -s -i http://127.0.0.1:27081/api/v1/send/EU-XX -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: text/xml' --data-binary @fti019.xml
```

```http
HTTP/1.1 400 Bad Request
Transfer-encoding: chunked
Content-type: application/json; charset=UTF-8

{"detail":"Unknown party: EU-XX","instance":"/api/v1/send/EU-XX","message":"Unknown party: EU-XX","status":400,"title":"Bad Request"}
```

### No token → 401

```bash
curl -s -i http://127.0.0.1:27081/api/v1/send/G2 -H 'Content-Type: text/xml' --data-binary @fti019.xml
```

```http
HTTP/1.1 401 Unauthorized
Transfer-encoding: chunked
Content-type: application/json; charset=UTF-8

{"detail":"Missing or invalid X-Internal-Service-Token","instance":"/api/v1/send/G2","message":"Missing or invalid X-Internal-Service-Token","status":401,"title":"Unauthorized"}
```

A wrong token (`X-Internal-Service-Token: nope`) gives the same answer.

### X-Request-Id that is not a UUID → 400

```bash
curl -s -i http://127.0.0.1:27081/api/v1/send/G2 -H "$T" -H 'X-Request-Id: abc' \
  -H 'Content-Type: text/xml' --data-binary @fti019.xml
```

```http
HTTP/1.1 400 Bad Request
Transfer-encoding: chunked
Content-type: application/json; charset=UTF-8

{"detail":"Invalid UUID string: abc","instance":"/api/v1/send/G2","message":"Invalid UUID string: abc","status":400,"title":"Bad Request"}
```

Without `X-Request-Id` the call works (`200`); edelivery uses a generated id.

### The peer rejects the message → 500

A payload whose root element the peer has no handler for:

```bash
RID=$(cat /proc/sys/kernel/random/uuid)
echo '<foo/>' > foo.xml
curl -s -i http://127.0.0.1:27081/api/v1/send/G2 -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: text/xml' --data-binary @foo.xml
```

```http
HTTP/1.1 500 Internal Server Error
Transfer-encoding: chunked
Content-type: application/json; charset=UTF-8

{"detail":"eDelivery request failed with status 500: EBMS:0002 - UNSUPPORTED_MESSAGE","message":"eDelivery request failed with status 500: EBMS:0002 - UNSUPPORTED_MESSAGE","status":500,"title":"Internal Server Error"}
```

G1 logs:

```text
warn edelivery.EDeliveryClient eDelivery SOAP fault from http://g2-edelivery:8081/services/msh: status=500, conversationId=G2:f5b6f919-e56b-44f1-8a86-8d22d1596afd:G1, reason=EBMS:0002 - UNSUPPORTED_MESSAGE, body_sha256=60f0ffdc2f5efca2fbe777ddb9341f3d6efccb4f3e355c50d82bbb13c4ccc187, body_size_bytes=902
error klite.RequestLogger <null> 172.24.0.1 POST /api/v1/send/G2: 500 in 55 ms - curl/8.5.0 - java.io.IOException: eDelivery request failed with status 500: EBMS:0002 - UNSUPPORTED_MESSAGE
```

A peer that cannot decrypt or verify the message answers the same way with
`EBMS:0004 - XML_PARSE_FAIL` or `EBMS:0101 - SIG_CRYPTO_FAIL`; see
[Create and exchange gate certificates](../../getting-started/certificates.md).

### No answer → 504 after 60 s

The root element `hello` is accepted by the peer but has no answer:

```bash
RID=$(cat /proc/sys/kernel/random/uuid)
echo '<hello/>' > hello.xml
time curl -s -i http://127.0.0.1:27081/api/v1/send/G2 -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: text/xml' --data-binary @hello.xml
```

```http
HTTP/1.1 504 Gateway Timeout
Date: Tue, 03 Nov 2026 16:01:44 GMT
Transfer-encoding: chunked
Content-type: application/json; charset=UTF-8

{"detail":"No data received after 1m","instance":"/api/v1/send/G2","message":"No data received after 1m","status":504,"title":"Gateway Timeout"}
real	1m0,036s
```

G2 logged `Handling 'hello' from G1:a7a0ad3f-8f8a-4b46-a922-f488761fdafe:G2` and
`POST /services/msh: 200`; G1 logged `POST /api/v1/send/G2: 504 in 60024 ms`. The same
`504` comes back when the peer's handler fails; see
[Dataset: unknown platform](./dataset-fti009.md#unknown-platform--504-after-60-s).

## Other edelivery endpoints

| Endpoint | Auth | Answer |
|---|---|---|
| `GET /health` | none | `200`, body `OK` (`text/xml`). There is no `/health/ready` on edelivery (`404`). |
| `GET /metrics` | none | Text metrics, e.g. `edelivery_messages_inbound_total_<type>`, `edelivery_messages_outbound_total_success`/`_failure`, `edelivery_async_response_timeouts_total`, `edelivery_async_handler_failures_total_<reason>`, `edelivery_parties_online_total`. |
| `GET /api/v1/openapi.json` | `X-Internal-Service-Token` | OpenAPI 3.1 of the send API (`401` without the token). |
| `GET`/`POST /services/msh` | none | [AS4 endpoint](./as4-endpoint.md) |

```bash
curl -s -i http://127.0.0.1:27081/health
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 2

OK
```

```bash
curl -s http://127.0.0.1:27081/metrics | head -12
```

```text
edelivery_async_response_timeouts_total 4
edelivery_parties_online_total 3
edelivery_messages_inbound_total_fti010getcmdsresponse 15
edelivery_messages_inbound_total_fti025lodgefollowupcommrequest 8
edelivery_messages_inbound_total_fti004uploadidentifierrequest 1
edelivery_messages_inbound_total_fti009getcmdsrequest 11
edelivery_messages_inbound_total_fti021searchidentifierresponse 26
edelivery_messages_inbound_total_fti030lodgefollowupcommresponse 10
edelivery_messages_inbound_total_fti019searchidentifierrequest 10
edelivery_inbound_dedup_unavailable_total 0
started_at{value="2026-11-03T13:54:02.307856683Z"} 1
requests_active 1
```

More on health checks: [Health and diagnostics](../operations/health.md).

## Notes

- Callers in the DSL: `efti/POST/api/v1/authority/search.yml` (fan-out to every `ONLINE` peer,
  62 s per peer), `authority/dataset.yml` (POST and GET), `authority/follow-up.yml`,
  `dataset-local.yml` and `follow-up-local.yml` (to eDelivery platforms), each with a 65 s timeout.
- While a call waits, edelivery polls ReSql (`claim_async_response`) every 50-250 ms and logs each
  poll at info level, so one `504` leaves a few hundred log lines.
- Two calls with the same `X-Request-Id` to the same party at the same time share one waiter key;
  the second registration replaces the first.
- The answer is returned as the peer sent it. edelivery does not check that its root element
  matches the request (an FTI019 could be answered with any registered answer type).
- edelivery rebuilds its outbound HTTP client on every registry refresh (every 10 s, once per
  party), as the repeated `Built TrustStore …` log lines show.
- Postman: folder **2. G2G -> peer gate**: "FTI019 search TST-001", "FTI009 dataset", "FTI025 follow-up",
  "send without token -> 401", "send to unknown party -> 400". The environment's `peer_gate` is the
  receiver; run it with each gate's environment for both directions.
