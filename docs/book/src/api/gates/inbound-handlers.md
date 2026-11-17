# Inbound handlers

When edelivery has accepted an AS4 request from a peer gate, it hands the decrypted FTI XML to
one of three Ruuter routes of its own gate and sends the route's answer back to the peer. The
routes are gate-internal: only edelivery calls them, with the internal service token.

| Route | FTI in | FTI out | DSL |
|---|---|---|---|
| `POST /efti/api/v1/consignments/search-xml?gateId=<own id>` | FTI019 | FTI021 | `DSL/Ruuter/efti/POST/api/v1/consignments/search-xml.yml` |
| `POST /efti/api/v1/dataset-xml` | FTI009 | FTI010 | `DSL/Ruuter/efti/POST/api/v1/dataset-xml.yml` |
| `POST /efti/api/v1/follow-up-xml` | FTI025 | FTI030 | `DSL/Ruuter/efti/POST/api/v1/follow-up-xml.yml` |

All three are on ruuter (G1 :27086, G2 :28086) and behind
`DSL/Ruuter/efti/POST/api/v1/.guard.yml`. The examples call G2 (`:28086`) directly, the way its
edelivery does. The token is the public dev value.

## How edelivery dispatches

`EftiMessageHandlers.kt` maps the root element of the decrypted payload to a call of
`RuuterClient.kt`:

| Root element | edelivery calls | Then |
|---|---|---|
| `FTI019SearchIdentifierRequest` | `POST {RUUTER_URL}/efti/api/v1/consignments/search-xml?gateId={key.senderId}` | sends the body to the peer |
| `FTI009GetCmdsRequest` | `POST {RUUTER_URL}/efti/api/v1/dataset-xml` | sends the body to the peer |
| `FTI025LodgeFollowUpCommRequest` | `POST {RUUTER_URL}/efti/api/v1/follow-up-xml` | sends the body to the peer |
| `FTI004UploadIdentifierRequest` | `POST {RUUTER_URL}/platforms/v1/consignments-xml` with `X-Platform-Id` = sender | sends the body to the platform ([Upload identifiers (eDelivery)](../platforms/upload-edelivery.md)) |
| `FTI010…`, `FTI021…`, `FTI029…`, `FTI030…` | nothing | releases the waiting [send](./send-api.md) call |
| `hello` | nothing | nothing |

Every call carries:

| Header | Value |
|---|---|
| `Content-Type` | `text/xml` |
| `X-Internal-Service-Token` | `INTERNAL_SERVICE_TOKEN` |
| `x-request-id` | The AS4 `ConversationId` |

The timeout is `RUUTER_TIMEOUT_SECONDS` (90). Any status ≥ 300 is turned into an exception without
the body: the peer gets no answer and its caller times out after 60 s. A `2xx` body is sent to the
peer whatever it contains.

`gateId` on `search-xml` is `RequestKey.senderId`. On an inbound message the key is built for the
reply (`receiverId` = the peer, `senderId` = this gate), so `gateId` is this gate's own id: G2's
edelivery calls `search-xml?gateId=G2` for a request from G1 (log line below). See
[Known issues](../../reference/known-issues.md).

In a non-production build edelivery uses `RuuterClientDev`: if the gate's own party id in the key
is `mock-edelivery` (the eDelivery mock platform, whose `eDeliveryUrl` points at G1's
edelivery), FTI009 and FTI025 go to `/mock-platform/v1/dataset-edelivery…` instead.

## Common request

| Header | Required | Value |
|---|---|---|
| `X-Internal-Service-Token` | yes | Must equal `INTERNAL_SERVICE_TOKEN`. |
| `Content-Type` | yes | `text/xml`. Ruuter puts a `text/xml` body into the field `xml`. `application/xml` and `text/plain` are not mapped → `400 {"error":"Field missing: xml"}`. A JSON body `{"xml":"<…>"}` also works (tried on `search-xml`). |
| `x-request-id` | no | UUID. Written into `RequestedSpecifiedQuery/ID` of the answer. Without it Ruuter generates one. |

| Body field | Required | Type | Meaning |
|---|---|---|---|
| `xml` | yes | string | The FTI request XML (the whole `text/xml` body). |

Common responses:

| Status | When | Body |
|---|---|---|
| `401` | Token missing or wrong | problem+json `UNAUTHORIZED`, "This endpoint requires a valid X-Internal-Service-Token." An `efti-internal.auth_denied` row is written to `audit_log`. |
| `400` | Body not mapped to `xml` | `{"error":"Field missing: xml"}` |

## `POST /efti/api/v1/consignments/search-xml`

`POST /efti/api/v1/consignments/search-xml` · on ruuter · `X-Internal-Service-Token` · source `DSL/Ruuter/efti/POST/api/v1/consignments/search-xml.yml`

| Query parameter | Required | Type | Meaning |
|---|---|---|---|
| `gateId` | no | string | Search only rows with this `gate_id`. Without it `[#OWN_GATE_ID]` is used. edelivery always sends the own gate id. |

Steps: xml-mapper `/api/v1/search/request-to-json` → ReSql `get_consignments` (`gateId`,
`criteria`, limit 20) → xml-mapper `/api/v1/search/response-to-xml`.

| Status | When | Body |
|---|---|---|
| `200` | Search ran | FTI021 (`text/xml`), with UILs or with the `No consignments found` note ([details](./search-fti019.md)) |
| `502` | ReSql unreachable or ≥ 300 | `{"error": "Data layer unavailable"}` (not run locally: it needs a stopped ReSql) |
| `401`, `400` | see above | |

### Search with the own gate id

```bash
T='X-Internal-Service-Token: dev-internal-service-token-change-me'
curl -s -i "http://127.0.0.1:28086/efti/api/v1/consignments/search-xml?gateId=G2" -H "$T" \
  -H 'Content-Type: text/xml' -H "x-request-id: $(cat /proc/sys/kernel/random/uuid)" --data-binary @fti019.xml
```

`fti019.xml` is the FTI019 for `G2-AB123` from [Identifier search](./search-fti019.md).

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 1310
<rsm:FTI021SearchIdentifierResponse …><rsm:ExchangedDocumentContext>…</rsm:ExchangedDocumentContext><rsm:ExchangedDocument><ID schemeID="RFC 9562-4">a6dda0ac-2930-48ab-b7e9-559b394a926f</ID><TypeCode>021</TypeCode><StatusCode>C</StatusCode><IssueDateTime><udt:DateTimeString format="207">20261103160252Z</udt:DateTimeString></IssueDateTime><RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">899a228d-5846-4846-90d4-28ea1a99b964</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G2</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">22222222-0000-4000-8000-000000000001</DatasetID><ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>G2-AB123</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
  </rsm:FTI021SearchIdentifierResponse>
```

`RequestedSpecifiedQuery/ID` is the `x-request-id` of this call (`899a228d-…`), not the query id
inside `fti019.xml`. Without `gateId` the answer is the same (default `[#OWN_GATE_ID]` = `G2`).

### Search with another gate id

```bash
curl -s -i "http://127.0.0.1:28086/efti/api/v1/consignments/search-xml?gateId=G1" -H "$T" \
  -H 'Content-Type: text/xml' -H "x-request-id: $(cat /proc/sys/kernel/random/uuid)" --data-binary @fti019.xml
```

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 1121

<rsm:FTI021SearchIdentifierResponse …>…<StatusCode>C</StatusCode>…<IncludedNote><IncludedNote><ContentCode>404</ContentCode><Content languageID="en">No consignments found</Content></IncludedNote></IncludedNote><RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">50df8468-b79c-429e-bbf8-e016d013f04a</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:EFTIIDInformation></rsm:EFTIIDInformation>
  </rsm:FTI021SearchIdentifierResponse>
```

G2's register has no rows with `gate_id` `G1`. This is why the `gateId` edelivery passes
must be the own id.

### Body that is not XML

```bash
echo '<nope>' > bad.xml
curl -s -i "http://127.0.0.1:28086/efti/api/v1/consignments/search-xml" -H "$T" \
  -H 'Content-Type: text/xml' -H "x-request-id: $(cat /proc/sys/kernel/random/uuid)" --data-binary @bad.xml
```

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 1121

<rsm:FTI021SearchIdentifierResponse …>…<IncludedNote><IncludedNote><ContentCode>404</ContentCode><Content languageID="en">No consignments found</Content></IncludedNote></IncludedNote>…<rsm:EFTIIDInformation></rsm:EFTIIDInformation>
  </rsm:FTI021SearchIdentifierResponse>
```

xml-mapper answers the parse error with `500`, the route does not check that status, and the
search ends with an empty FTI021.

### Wrong content type → 400

```bash
curl -s -i "http://127.0.0.1:28086/efti/api/v1/consignments/search-xml?gateId=G2" -H "$T" \
  -H 'Content-Type: application/xml' --data-binary @fti019.xml
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 30

{"error":"Field missing: xml"}
```

### No token → 401

```bash
curl -s -i "http://127.0.0.1:28086/efti/api/v1/consignments/search-xml" \
  -H 'Content-Type: text/xml' --data-binary @fti019.xml
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 176

{"code":"UNAUTHORIZED","detail":"This endpoint requires a valid X-Internal-Service-Token.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

## `POST /efti/api/v1/dataset-xml`

`POST /efti/api/v1/dataset-xml` · on ruuter · `X-Internal-Service-Token` · source `DSL/Ruuter/efti/POST/api/v1/dataset-xml.yml`, `dataset-local.yml`

No query parameters. Steps: xml-mapper `/api/v1/dataset/request-to-json` → template
`api/v1/dataset-local` (registry lookup of `uil.platformId`, then the platform: REST
`GET <baseUrl>/v1/dataset/<id>?subsets=…`, or FTI009 via the send API for an eDelivery platform) →
xml-mapper `/api/v1/dataset/response-to-xml`. The behaviour of `dataset-local` is in
[Local platform handlers](../internal/local-handlers.md).

| Status | When | Body |
|---|---|---|
| `200` | Platform answered | FTI010 (`text/xml`) |
| `200` | `dataset-local` failed (unknown or `DISABLED` platform; registry, mapper or platform unreachable) | xml-mapper's error JSON, with `content-type: text/xml` (see below) |
| `500` | The XML does not parse | Ruuter `{"error":"step 'call_dataset' (template) … failed …"}` |
| `401`, `400` | see above | |

### Dataset found

```bash
curl -s -i http://127.0.0.1:28086/efti/api/v1/dataset-xml -H "$T" -H 'Content-Type: text/xml' \
  -H "x-request-id: $(cat /proc/sys/kernel/random/uuid)" --data-binary @fti009.xml
```

`fti009.xml` is the FTI009 for `G2/mock/22222222-0000-4000-8000-000000000001`, subsets `EU01`,
`EU02`, from [Dataset](./dataset-fti009.md).

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 1718
<rsm:FTI010GetCmdsResponse …>…<TypeCode>010</TypeCode><StatusCode>C</StatusCode>…<RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">f5c77827-81be-4cea-9353-69d215fad033</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:MessageInformation><SubsetID>EU01</SubsetID><SubsetID>EU02</SubsetID></rsm:MessageInformation>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G2</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">22222222-0000-4000-8000-000000000001</DatasetID></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
    <rsm:SpecifiedSupplyChainConsignment><GrossWeightMeasure unitCode="KGM">15000.00</GrossWeightMeasure>
  <PackageQuantity>120</PackageQuantity>
  …</rsm:SpecifiedSupplyChainConsignment>
  </rsm:FTI010GetCmdsResponse>
```

### Unknown platform → 200 with an error body

```bash
curl -s -i http://127.0.0.1:28086/efti/api/v1/dataset-xml -H "$T" -H 'Content-Type: text/xml' \
  -H "x-request-id: $(cat /proc/sys/kernel/random/uuid)" --data-binary @fti009-unknownpf.xml
```

`fti009-unknownpf.xml` asks for platform `nope`.

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 206

{"detail":"Cannot create DatasetResponse from {}: missing uil","instance":"/api/v1/dataset/response-to-xml","message":"Cannot create DatasetResponse from {}: missing uil","status":400,"title":"Bad Request"}
```

`dataset-local` answered `404 Platform Not Found`; a `template:` step only returns the body, which
arrived here as `{}`; xml-mapper refused it with `400`; the route returns that body with its own
`200`. edelivery forwards it to the peer, which refuses it as `UNSUPPORTED_MESSAGE`, and the peer's
caller times out ([run over AS4](./dataset-fti009.md#unknown-platform--504-after-60-s)).

### Body that is not XML → 500

```bash
curl -s -i http://127.0.0.1:28086/efti/api/v1/dataset-xml -H "$T" -H 'Content-Type: text/xml' \
  -H "x-request-id: $(cat /proc/sys/kernel/random/uuid)" --data-binary @bad.xml
```

```http
HTTP/1.1 500 Internal Server Error
content-type: application/json
content-length: 230

{"error":"step 'call_dataset' (template) in project 'efti' failed -> caused by: step 'get_platform' (http) in project 'efti' failed -> caused by: Script evaluation error: TypeError: cannot convert 'null' or 'undefined' to object"}
```

### Wrong token → 401

```bash
curl -s -i http://127.0.0.1:28086/efti/api/v1/dataset-xml -H 'X-Internal-Service-Token: nope' \
  -H 'Content-Type: text/xml' --data-binary @fti009.xml
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 176

{"code":"UNAUTHORIZED","detail":"This endpoint requires a valid X-Internal-Service-Token.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

## `POST /efti/api/v1/follow-up-xml`

`POST /efti/api/v1/follow-up-xml` · on ruuter · `X-Internal-Service-Token` · source `DSL/Ruuter/efti/POST/api/v1/follow-up-xml.yml`, `follow-up-local.yml`

No query parameters. Steps: xml-mapper `/api/v1/followup/request-to-json` → template
`api/v1/follow-up-local` (`follow_up_log` row, registry lookup, forward to the platform) →
xml-mapper `/api/v1/followup/response-to-xml`. The `x-request-id` is the follow-up's query id: the
same id with the same content is delivered once ([Follow-up](./follow-up-fti025.md)).

| Status | When | Body |
|---|---|---|
| `201` | Delivered to the platform, or a repeat of a delivered follow-up | FTI030 (`text/xml`) |
| `201` | `follow-up-local` failed (unknown platform, platform error, log write failed) or the XML does not parse | xml-mapper's error JSON with `content-type: text/xml` |
| `409` | Same follow-up still `PENDING` | problem+json `FOLLOW_UP_IN_PROGRESS` (not run locally) |
| `401`, `400` | see above | |

### Delivered

A follow-up for a new dataset id at G2's `mock`, built with G2's xml-mapper:

```bash
RID=$(cat /proc/sys/kernel/random/uuid); DS=$(cat /proc/sys/kernel/random/uuid)
curl -s http://127.0.0.1:28082/api/v1/followup/request-to-xml -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: application/json' \
  -d '{"uil":{"gateId":"G2","platformId":"mock","datasetId":"'$DS'"},"referenceIds":[],"message":"Direct call test"}' \
  > fti025-direct.xml
curl -s -i http://127.0.0.1:28086/efti/api/v1/follow-up-xml -H "$T" -H 'Content-Type: text/xml' \
  -H "x-request-id: $(cat /proc/sys/kernel/random/uuid)" --data-binary @fti025-direct.xml
```

```http
HTTP/1.1 201 Created
content-type: text/xml
content-length: 1158
<rsm:FTI030LodgeFollowUpCommResponse …>…<TypeCode>030</TypeCode><StatusCode>C</StatusCode>…<RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">29e7170a-dc15-4401-92b7-c9f101f33df4</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G2</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">29ee5aeb-051a-40ce-859f-c232d32b9093</DatasetID></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
  </rsm:FTI030LodgeFollowUpCommResponse>
```

### Unknown platform → 201 with an error body

Same request with `"platformId":"nope"`:

```http
HTTP/1.1 201 Created
content-type: text/xml
content-length: 197

{"detail":"Cannot create UIL from {}: missing platformId","instance":"/api/v1/followup/response-to-xml","message":"Cannot create UIL from {}: missing platformId","status":400,"title":"Bad Request"}
```

The body `<nope>` gives the same `201` and the same JSON.

## Notes

- The success status differs: `search-xml` and `dataset-xml` answer `200`, `follow-up-xml` `201`.
  edelivery accepts any `2xx`.
- `dataset-xml` and `follow-up-xml` do not check the template result before mapping it to XML, so
  every failure of `dataset-local` / `follow-up-local` becomes a `2xx` with a JSON body. Over AS4
  that means no answer and a `504` at the requesting gate after 60 s, instead of an FTI error
  answer. `search-xml` does not check xml-mapper's status either.
- `docs/specs/api_endpoints.md` says these routes "stay public" and are reached "after AS4 mTLS".
  The DSL guard requires `X-Internal-Service-Token` (checked: `401` without it), and there is no
  mTLS on the AS4 hop in this stack; peers are authenticated by the AS4 signature.
- `docs/specs/openapi.generated.yaml` lists `application/json` as the request body of `dataset-xml`
  and `follow-up-xml`; edelivery sends `text/xml`.
- Postman: folder **3. G2G inbound handlers and the AS4 edge**: "search-xml (FTI019 -> FTI021)",
  "dataset-xml (FTI009 -> FTI010)", "follow-up-xml (FTI025 -> FTI030)".
