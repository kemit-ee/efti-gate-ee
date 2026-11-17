# Follow-up: FTI025 / FTI030

A gate passes an authority's follow-up message about a dataset (a question or remark, optionally
with files) to the peer gate that holds the dataset. The peer forwards it to the platform and
confirms with `FTI030LodgeFollowUpCommResponse`.

In this gate, FTI025 is sent by `efti/POST/api/v1/authority/follow-up.yml` when the UIL's `gateId`
is another gate (see [follow-up](../authorities/follow-up.md)), and answered by the inbound handler
[`follow-up-xml`](./inbound-handlers.md#post-eftiapiv1follow-up-xml).

`FTI025` → `FTI030` · AS4 user messages on `/services/msh` ([AS4 endpoint](./as4-endpoint.md)) · sent with [`POST /api/v1/send/{partyId}`](./send-api.md) · built and parsed by `code/xml-mapper/src/efti/FollowUpRoutes.kt`, `xml/fti/FTIMessages.kt`

## FTI025LodgeFollowUpCommRequest

Built by G1's xml-mapper and sent to G2. A new random dataset id was used so that no seeded
dataset gets a follow-up. Input:

```json
{"uil":{"gateId":"G2","platformId":"mock","datasetId":"0404186e-d4c2-4ba8-bfbc-b31af49154e2"},
 "referenceIds":["8414e5dd-6184-4578-b159-cfa1a63c0d90"],
 "message":"Seal number differs from the CMR. Please check.",
 "files":[{"fileName":"seal.txt","base64Content":"UGhvdG8gb2Ygc2VhbCA0NzEx"}]}
```

Output (indentation added):

```xml
<rsm:FTI025LodgeFollowUpCommRequest xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI025LodgeFollowUpCommRequest:1" xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34" xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
  <rsm:ExchangedDocumentContext>
    <MessageStandardSpecifiedDocumentContextParameter>
      <ID>eFTI</ID>
      <SpecifiedDocumentVersion>
        <ID>1.0</ID>
      </SpecifiedDocumentVersion>
    </MessageStandardSpecifiedDocumentContextParameter>
  </rsm:ExchangedDocumentContext>
  <rsm:ExchangedDocument>
    <ID schemeID="RFC 9562-4">f9853ead-b52e-47d7-bee8-c4f6a304ad02</ID>
    <TypeCode>025</TypeCode>
    <IssueDateTime>
      <udt:DateTimeString format="207">20261103160120Z</udt:DateTimeString>
    </IssueDateTime>
    <ReferencedID schemeVersionID="RFC 9562-4">8414e5dd-6184-4578-b159-cfa1a63c0d90</ReferencedID>
    <RequestedSpecifiedQuery>
      <ID schemeID="RFC 9562-4">aa05bd78-6d75-4987-9078-f957b7cba762</ID>
    </RequestedSpecifiedQuery>
  </rsm:ExchangedDocument>
  <rsm:MessageInformation>
    <FollowUp languageID="en">Seal number differs from the CMR. Please check.</FollowUp>
    <AttachedSpecifiedBinaryFile>
      <FileName>seal.txt</FileName>
      <MIMECode>text/plain</MIMECode>
      <IncludedBinaryObject format="base64Binary">UGhvdG8gb2Ygc2VhbCA0NzEx</IncludedBinaryObject>
    </AttachedSpecifiedBinaryFile>
  </rsm:MessageInformation>
  <rsm:EFTIIDInformation>
    <UniqueIDSetUniqueIDSet>
      <GateID>G2</GateID>
      <PlatformID>mock</PlatformID>
      <DatasetID schemeID="RFC 9562-4">0404186e-d4c2-4ba8-bfbc-b31af49154e2</DatasetID>
    </UniqueIDSetUniqueIDSet>
  </rsm:EFTIIDInformation>
</rsm:FTI025LodgeFollowUpCommRequest>
```

| Element | JSON field | Meaning |
|---|---|---|
| `ExchangedDocument/TypeCode` | | `025`. |
| `ExchangedDocument/ReferencedID` (0..n) | `referenceIds[]` | Ids of earlier messages the follow-up refers to (e.g. the dataset request). UUIDs. |
| `ExchangedDocument/RequestedSpecifiedQuery/ID` | | Query id = `X-Request-Id` of the xml-mapper call = AS4 `ConversationId`. The receiving gate uses it to detect a repeat. |
| `MessageInformation/FollowUp` | `message` | The text, `languageID="en"` is always written. Not XML-escaped by the mapper. |
| `MessageInformation/AttachedSpecifiedBinaryFile` (0..n) | `files[]` | One per attachment. |
| `…/FileName` | `files[].fileName` | File name. |
| `…/MIMECode` | `files[].mimeType` | Optional in JSON; derived from the file name extension, `application/octet-stream` if unknown. |
| `…/IncludedBinaryObject` | `files[].base64Content` | File content, base64, `format="base64Binary"`. |
| `EFTIIDInformation/UniqueIDSetUniqueIDSet` | `uil` | The dataset the follow-up is about. `PlatformID` must be an `ONLINE` platform of the receiving gate. |

Attachments travel inside the FTI XML, so they count against the AS4 limits: 10 MiB body at the
edge and 10 MiB of XML after decompression ([AS4 endpoint](./as4-endpoint.md)).

## FTI030LodgeFollowUpCommResponse

```bash
RID=aa05bd78-6d75-4987-9078-f957b7cba762   # the X-Request-Id used to build fti025.xml
T='X-Internal-Service-Token: dev-internal-service-token-change-me'
curl -s -i http://127.0.0.1:27081/api/v1/send/G2 -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: text/xml' --data-binary @fti025.xml
```

```http
HTTP/1.1 200 OK
Date: Tue, 03 Nov 2026 16:01:21 GMT
Content-type: text/xml; charset=UTF-8
Content-length: 1158
<rsm:FTI030LodgeFollowUpCommResponse xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI030LodgeFollowUpCommResponse:1" xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34" xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34"><rsm:ExchangedDocumentContext><MessageStandardSpecifiedDocumentContextParameter><ID>eFTI</ID><SpecifiedDocumentVersion><ID>1.0</ID></SpecifiedDocumentVersion></MessageStandardSpecifiedDocumentContextParameter></rsm:ExchangedDocumentContext><rsm:ExchangedDocument><ID schemeID="RFC 9562-4">a1053cef-34b1-411b-b387-367755b80922</ID><TypeCode>030</TypeCode><StatusCode>C</StatusCode><IssueDateTime><udt:DateTimeString format="207">20261103160120Z</udt:DateTimeString></IssueDateTime><RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">aa05bd78-6d75-4987-9078-f957b7cba762</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:EFTIIDInformation><UniqueIDSetUniqueIDSet><GateID>G2</GateID><PlatformID>mock</PlatformID><DatasetID schemeID="RFC 9562-4">0404186e-d4c2-4ba8-bfbc-b31af49154e2</DatasetID></UniqueIDSetUniqueIDSet></rsm:EFTIIDInformation>
  </rsm:FTI030LodgeFollowUpCommResponse>
```

| Element | Meaning |
|---|---|
| `ExchangedDocument/TypeCode` | `030`. |
| `ExchangedDocument/StatusCode` | `C`. |
| `ExchangedDocument/RequestedSpecifiedQuery/ID` | The `ConversationId` of the request. |
| `EFTIIDInformation/UniqueIDSetUniqueIDSet` | The UIL the follow-up was delivered for. |

FTI030 has no field for the platform's answer; it only confirms delivery.

## How the receiving gate handles FTI025

1. edelivery accepts the AS4 message and calls `POST <RUUTER_URL>/efti/api/v1/follow-up-xml` with
   `x-request-id` = `ConversationId`.
2. Ruuter converts the XML to `{uil, referenceIds, message, files}` and runs `follow-up-local` as
   a template:
   - writes a `PENDING` row to `follow_up_log`. With a UUID request id, a repeat of a follow-up
     that was already `DELIVERED` is answered at once without a new delivery; a repeat while the
     first is still `PENDING` (under 120 s) gets `409 FOLLOW_UP_IN_PROGRESS`;
   - looks up the platform; REST platform: `POST <baseUrl>/v1/dataset/<datasetId>/follow-up`
     with the JSON body and `X-Dataset-Request-ID` = the first `referenceIds` entry; eDelivery
     platform: a new FTI025 through the send API;
   - writes `DELIVERED` or `FAILED`.
3. xml-mapper `/api/v1/followup/response-to-xml` builds the FTI030 from the UIL.
4. edelivery sends it to the requesting gate.

The rows G2 wrote for the follow-up above:

```bash
docker exec efti-g2-database psql -U efti -d efti -c "SELECT requesting_gate_id, dataset_request_id, destination_platform_id, status, query_id, created_at FROM follow_up_log WHERE dataset_request_id='0404186e-d4c2-4ba8-bfbc-b31af49154e2' ORDER BY created_at"
```

```text
 requesting_gate_id |          dataset_request_id          | destination_platform_id |  status   |               query_id               |          created_at
--------------------+--------------------------------------+-------------------------+-----------+--------------------------------------+-------------------------------
 G2              | 0404186e-d4c2-4ba8-bfbc-b31af49154e2 | mock                    | PENDING   | aa05bd78-6d75-4987-9078-f957b7cba762 | 2026-11-03 16:01:20.950567+00
 G2              | 0404186e-d4c2-4ba8-bfbc-b31af49154e2 | mock                    | DELIVERED | aa05bd78-6d75-4987-9078-f957b7cba762 | 2026-11-03 16:01:20.985495+00
(2 rows)
```

`requesting_gate_id` is `G2`, the receiving gate: `follow-up-local` fills both
`requestingGateId` and `destinationGateId` from `uil.gateId`. Which gate sent the follow-up is not
recorded.

## Repeating the same follow-up

The same `fti025.xml` sent again with the same `X-Request-Id` 9 s later:

```http
HTTP/1.1 200 OK
Date: Tue, 03 Nov 2026 16:01:30 GMT
Content-type: text/xml; charset=UTF-8
Content-length: 1161
```

The answer is again an FTI030, G2 logged `POST http://ruuter:8086/efti/api/v1/follow-up-xml in
75ms - 201`, and no new `follow_up_log` rows were written: the platform did not get it twice.

## Errors

| Case | Result |
|---|---|
| Unknown or `DISABLED` platform | `follow-up-xml` answers `201` with xml-mapper's error JSON instead of an FTI030 (direct call shown in [Inbound handlers](./inbound-handlers.md#post-eftiapiv1follow-up-xml)). Over AS4 that JSON is refused by the requesting gate as `UNSUPPORTED_MESSAGE`, as in the [dataset case](./dataset-fti009.md#unknown-platform--504-after-60-s), so the requester gets `504` after 60 s (run with `platformId` `nope`: G2 `POST …/follow-up-xml in 62ms - 201`, `payload_size_bytes=197`, G1 `reason=UNSUPPORTED_MESSAGE`, caller `504 in 60019 ms`). |
| Platform unreachable or answers ≥ 300 | Logged `FAILED`; then as above. Not run locally (the mock platform cannot be stopped). |
| Same follow-up still `PENDING` | `409 FOLLOW_UP_IN_PROGRESS` from `follow-up-local`. Not run locally: it needs a delivery that is still in flight. |
| Peer rejects the AS4 message | `500` from the send API with the ebMS reason ([send API](./send-api.md)). |

## Notes

- Also run G2 → G1 (a new dataset id at G1's `mock`): `200` with an FTI030.
- `follow-up-xml` checks `followup_response.code == 'FOLLOW_UP_IN_PROGRESS'` on the template
  result. On the error paths that were run, the template result was an empty object (`Cannot
  create UIL from {}`), so it is not certain that this check can ever see the 409 body.
- Postman: **2. G2G -> peer gate / FTI025 follow-up**; run it with each gate's environment for both directions.
