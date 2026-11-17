# Dataset: FTI009 / FTI010

A gate asks the peer gate that holds a dataset for its content. The request
`FTI009GetCmdsRequest` names the dataset by its [UIL](../../data/uil.md) and lists the
[subsets](../../data/subsets.md) wanted; the answer `FTI010GetCmdsResponse` carries the
consignment (`SpecifiedSupplyChainConsignment`), which the receiving gate gets from the platform.

In this gate, FTI009 is sent by `efti/POST/api/v1/authority/dataset.yml` (and its GET variant)
when the UIL's `gateId` is another gate (see [dataset](../authorities/dataset.md)), and answered by
the inbound handler [`dataset-xml`](./inbound-handlers.md#post-eftiapiv1dataset-xml).

`FTI009` → `FTI010` · AS4 user messages on `/services/msh` ([AS4 endpoint](./as4-endpoint.md)) · sent with [`POST /api/v1/send/{partyId}`](./send-api.md) · built and parsed by `code/xml-mapper/src/efti/DatasetRoutes.kt`, `xml/fti/FTIMessages.kt`

## FTI009GetCmdsRequest

Built by G1's xml-mapper from
`{"uil":{"gateId":"G2","platformId":"mock","datasetId":"22222222-0000-4000-8000-000000000001"},"subsets":["EU01","EU02"]}`
and sent to G2 (indentation added):

```xml
<rsm:FTI009GetCmdsRequest xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI009GetCmdsRequest:1" xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34" xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
  <rsm:ExchangedDocumentContext>
    <MessageStandardSpecifiedDocumentContextParameter>
      <ID>eFTI</ID>
      <SpecifiedDocumentVersion>
        <ID>1.0</ID>
      </SpecifiedDocumentVersion>
    </MessageStandardSpecifiedDocumentContextParameter>
  </rsm:ExchangedDocumentContext>
  <rsm:ExchangedDocument>
    <ID schemeID="RFC 9562-4">a598fbfc-c908-4428-9aba-8b0fd2d138d4</ID>
    <TypeCode>009</TypeCode>
    <IssueDateTime>
      <udt:DateTimeString format="207">20261103160049Z</udt:DateTimeString>
    </IssueDateTime>
    <RequestedSpecifiedQuery>
      <ID schemeID="RFC 9562-4">ffea4bfa-e49a-46cd-9c3b-153b7fb73ada</ID>
    </RequestedSpecifiedQuery>
  </rsm:ExchangedDocument>
  <rsm:MessageInformation>
    <SubsetID>EU01</SubsetID>
    <SubsetID>EU02</SubsetID>
  </rsm:MessageInformation>
  <rsm:EFTIIDInformation>
    <UniqueIDSetUniqueIDSet>
      <GateID>G2</GateID>
      <PlatformID>mock</PlatformID>
      <DatasetID schemeID="RFC 9562-4">22222222-0000-4000-8000-000000000001</DatasetID>
    </UniqueIDSetUniqueIDSet>
  </rsm:EFTIIDInformation>
</rsm:FTI009GetCmdsRequest>
```

| Element | Meaning |
|---|---|
| `ExchangedDocument/TypeCode` | `009`. |
| `ExchangedDocument/RequestedSpecifiedQuery/ID` | Query id = the `X-Request-Id` of the xml-mapper call = the AS4 `ConversationId`. |
| `MessageInformation/SubsetID` (0..n) | Subsets wanted, e.g. `EU01`, `EU02`; codes in [eFTI subsets](../../data/subsets.md). None = the whole dataset. |
| `EFTIIDInformation/UniqueIDSetUniqueIDSet/GateID` | The gate that holds the dataset; should be the receiving gate. |
| `…/PlatformID` | The platform that holds the dataset; must be an `ONLINE` platform in the receiving gate's registry. |
| `…/DatasetID` | The dataset id at that platform. |

## FTI010GetCmdsResponse

G2's answer, as returned by G1's send API (indentation added):

```xml
<rsm:FTI010GetCmdsResponse xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI010GetCmdsResponse:1" xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34" xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
  <rsm:ExchangedDocumentContext>
    <MessageStandardSpecifiedDocumentContextParameter>
      <ID>eFTI</ID>
      <SpecifiedDocumentVersion>
        <ID>1.0</ID>
      </SpecifiedDocumentVersion>
    </MessageStandardSpecifiedDocumentContextParameter>
  </rsm:ExchangedDocumentContext>
  <rsm:ExchangedDocument>
    <ID schemeID="RFC 9562-4">4aad9ef7-06cb-4438-bcae-e8e2ef63f61e</ID>
    <TypeCode>010</TypeCode>
    <StatusCode>C</StatusCode>
    <IssueDateTime>
      <udt:DateTimeString format="207">20261103160049Z</udt:DateTimeString>
    </IssueDateTime>
    <RequestedSpecifiedQuery>
      <ID schemeID="RFC 9562-4">ffea4bfa-e49a-46cd-9c3b-153b7fb73ada</ID>
    </RequestedSpecifiedQuery>
  </rsm:ExchangedDocument>
  <rsm:MessageInformation>
    <SubsetID>EU01</SubsetID>
    <SubsetID>EU02</SubsetID>
  </rsm:MessageInformation>
  <rsm:EFTIIDInformation>
    <UniqueIDSetUniqueIDSet>
      <GateID>G2</GateID>
      <PlatformID>mock</PlatformID>
      <DatasetID schemeID="RFC 9562-4">22222222-0000-4000-8000-000000000001</DatasetID>
    </UniqueIDSetUniqueIDSet>
  </rsm:EFTIIDInformation>
  <rsm:SpecifiedSupplyChainConsignment>
    <GrossWeightMeasure unitCode="KGM">15000.00</GrossWeightMeasure>
    <PackageQuantity>120</PackageQuantity>
    <MainCarriageLogisticsTransportMovement>
      <ModeCode>3</ModeCode>
      <UsedLogisticsTransportMeans>
        <ID schemeAgencyID="5">MOCK-PLATE-1</ID>
        <RegistrationTradeCountry>
          <ID>EE</ID>
        </RegistrationTradeCountry>
      </UsedLogisticsTransportMeans>
    </MainCarriageLogisticsTransportMovement>
  </rsm:SpecifiedSupplyChainConsignment>
</rsm:FTI010GetCmdsResponse>
```

| Element | Meaning |
|---|---|
| `ExchangedDocument/TypeCode` | `010`. |
| `ExchangedDocument/StatusCode` | `C`. Written whenever the platform answered with a body. |
| `ExchangedDocument/RequestedSpecifiedQuery/ID` | The `ConversationId` of the request. |
| `MessageInformation/SubsetID` | The subsets of the request, echoed. |
| `EFTIIDInformation/UniqueIDSetUniqueIDSet` | The UIL of the request, echoed. |
| `SpecifiedSupplyChainConsignment` | The consignment as the platform returned it (its root element is replaced by `rsm:SpecifiedSupplyChainConsignment`). Fields: [Dataset content (FTI010)](../../data/dataset-fields.md). |

If the platform itself returns a complete FTI010 document (one that contains `ExchangedDocument`),
the gate passes it through unchanged.

The content above is the mock platform's minimal fixture: the mock returns `MOCK-PLATE-1` for any
dataset id except four fixed ones, and it does not apply the subsets (see
[Mock platform](../platforms/mock-platform.md)). The values in the gate's register for
`22222222-…-0001` (`G2-AB123`) play no part.

## How the receiving gate handles FTI009

1. edelivery accepts the AS4 message and calls `POST <RUUTER_URL>/efti/api/v1/dataset-xml` with
   the XML and `x-request-id` = `ConversationId`.
2. Ruuter converts it to `{uil, subsets}` (xml-mapper `/api/v1/dataset/request-to-json`) and runs
   `dataset-local` as a template:
   - looks up `uil.platformId` in the registry (`/platforms/<id>.json`); unknown or `DISABLED` →
     "Platform Not Found";
   - REST platform: `GET <baseUrl>/v1/dataset/<datasetId>?subsets=EU01,EU02` with the registry
     `headers` (here `x-api-key`);
   - eDelivery platform (has `eDeliveryCert`): builds a new FTI009 and sends it with the send API.
3. xml-mapper `/api/v1/dataset/response-to-xml` wraps the platform's XML into the FTI010.
4. edelivery sends the FTI010 to the requesting gate.

The gate does not look at its own register here: it does not check that the dataset exists, that
`GateID` is its own id, or which authority is asking. The subsets are taken from the request as
they are.

## Errors

### Unknown dataset

The receiving gate forwards any dataset id to the platform, so the platform decides. The mock
platform answers every id; a request for a random id was answered `200` with the same
`MOCK-PLATE-1` FTI010 and the requested `DatasetID` echoed in the UIL. What a real platform's `404`
does is not tested here (the mock never sends one); `dataset-local` passes the platform's status
on, and a non-200 from the handler ends like the case below.

### Unknown platform → 504 after 60 s

```bash
RID=$(cat /proc/sys/kernel/random/uuid)
T='X-Internal-Service-Token: dev-internal-service-token-change-me'
curl -s http://127.0.0.1:27082/api/v1/dataset/request-to-xml -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: application/json' \
  -d '{"uil":{"gateId":"G2","platformId":"nope","datasetId":"22222222-0000-4000-8000-000000000001"},"subsets":["EU01"]}' \
  > fti009-unknownpf.xml
time curl -s -i http://127.0.0.1:27081/api/v1/send/G2 -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: text/xml' --data-binary @fti009-unknownpf.xml
```

```http
HTTP/1.1 504 Gateway Timeout
Date: Tue, 03 Nov 2026 16:02:00 GMT
Transfer-encoding: chunked
Content-type: application/json; charset=UTF-8

{"detail":"No data received after 1m","instance":"/api/v1/send/G2","message":"No data received after 1m","status":504,"title":"Gateway Timeout"}
real	1m0,097s
```

What happened, from the two logs (`15b5c0cf-…` is the request id):

```text
G2  info  Handling 'FTI009GetCmdsRequest' from G1:15b5c0cf-9a21-4df9-be12-ad6853a18164:G2
G2  info  POST /services/msh: 200 in 41 ms
G2  info  POST http://ruuter:8086/efti/api/v1/dataset-xml in 111ms - 200
G2  info  Sending message to http://g1-edelivery:8081/services/msh: conversationId=G1:15b5c0cf-9a21-4df9-be12-ad6853a18164:G2 payload_size_bytes=206
G1  error Error when processing message: audit_ref=2fc4d604-41a2-4d98-bcf5-48a0ef6bd3b6 reason=UNSUPPORTED_MESSAGE … multipart_part_count=2
G2  warn  eDelivery SOAP fault from http://g1-edelivery:8081/services/msh: status=500, conversationId=G1:15b5c0cf-…:G2, reason=EBMS:0002 - UNSUPPORTED_MESSAGE, …
G2  error handler-dispatch failure: audit_ref=661ff595-ebb7-4e6d-bef8-f26e600905d4 name=handler reason=UNAVAILABLE
G1  error POST /api/v1/send/G2: 504 in 60069 ms - curl/8.5.0 - java.util.concurrent.TimeoutException: No data received after 1m
```

G2's `dataset-xml` answered `200` with a 206-byte body that is not an FTI010 but xml-mapper's
error JSON (see [Inbound handlers](./inbound-handlers.md#post-eftiapiv1dataset-xml)). edelivery
sent that JSON to G1 as the answer; G1 refused it as `UNSUPPORTED_MESSAGE` because its root
is not an FTI element; so no answer reached the waiting call. There is no FTI010 error answer
(`StatusCode` `E`) in this gate.

### Platform unreachable

Not run locally: the only REST platform is the mock inside the gate's own Ruuter, and the stack
must not be stopped. From the DSL: `dataset-local` answers `502`
`{"error":"The platform is unreachable",…}`; the template result is then mapped like the unknown
platform case, so the requester would also see `504` after 60 s.

### Peer rejects the message

`500` from the send API with the ebMS reason, see [send API](./send-api.md).

## Notes

- Also run G2 → G1 (dataset `11111111-0000-4000-8000-000000000003`): `200` with an FTI010.
- Postman: **2. G2G -> peer gate / FTI009 dataset**; run it with each gate's environment for both directions.
