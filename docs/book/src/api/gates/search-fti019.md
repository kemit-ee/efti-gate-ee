# Identifier search: FTI019 / FTI021

A gate asks a peer gate which consignments it holds that match a set of search criteria. The
request is `FTI019SearchIdentifierRequest`, the answer `FTI021SearchIdentifierResponse`, a list of
UILs (gate, platform, dataset id) with the identifiers the platform registered for each.

In this gate, FTI019 is sent by the cross-gate fan-out of an authority search
(`efti/POST/api/v1/authority/search.yml`, see [search](../authorities/search.md)) to every
`ONLINE` peer, and answered by the inbound handler
[`consignments/search-xml`](./inbound-handlers.md#post-eftiapiv1consignmentssearch-xml).

`FTI019` → `FTI021` · AS4 user messages on `/services/msh` ([AS4 endpoint](./as4-endpoint.md)) · sent with [`POST /api/v1/send/{partyId}`](./send-api.md) · built and parsed by `code/xml-mapper/src/efti/SearchRoutes.kt`, `xml/fti/FTIMessages.kt`, `xml/fti/ParameterSearchCriteria.kt`

## FTI019SearchIdentifierRequest

This request was built by G1's xml-mapper from `{"mainTransportId":{"id":"G2-AB123","operator":"EQ"}}`
and sent to G2 (`X-Request-Id: 78ce1c60-05ca-4708-a022-7504a9f82a05`). Indentation added; the
gate writes it on one line plus two indented lines.

```xml
<rsm:FTI019SearchIdentifierRequest xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI019SearchIdentifierRequest:1" xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34" xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
  <rsm:ExchangedDocumentContext>
    <MessageStandardSpecifiedDocumentContextParameter>
      <ID>eFTI</ID>
      <SpecifiedDocumentVersion>
        <ID>1.0</ID>
      </SpecifiedDocumentVersion>
    </MessageStandardSpecifiedDocumentContextParameter>
  </rsm:ExchangedDocumentContext>
  <rsm:ExchangedDocument>
    <ID schemeID="RFC 9562-4">33c59ee1-0326-4b09-872c-5e472fe28126</ID>
    <TypeCode>019</TypeCode>
    <IssueDateTime>
      <udt:DateTimeString format="207">20261103155948Z</udt:DateTimeString>
    </IssueDateTime>
    <RequestedSpecifiedQuery>
      <ID schemeID="RFC 9562-4">78ce1c60-05ca-4708-a022-7504a9f82a05</ID>
    </RequestedSpecifiedQuery>
  </rsm:ExchangedDocument>
  <rsm:EFTIIDInformation>
    <ParameterSearchCriteria>
      <MainCarriageTransportMeansIDParameterScope>
        <SubtypeCode>EQ</SubtypeCode>
        <ID>G2-AB123</ID>
      </MainCarriageTransportMeansIDParameterScope>
    </ParameterSearchCriteria>
  </rsm:EFTIIDInformation>
</rsm:FTI019SearchIdentifierRequest>
```

| Element | Meaning |
|---|---|
| `rsm:FTI019SearchIdentifierRequest` | Root. Namespaces: `rsm` = `urn:eu:move:eFTI:data:standard:FTI019SearchIdentifierRequest:1`, default = `…:ReusableAggregateBusinessInformationEntity:34`, `udt` = `…:UnqualifiedDataType:34`. edelivery dispatches on the local name of the root element. |
| `ExchangedDocumentContext/…/ID` | `eFTI`, version `1.0`. Always written, not checked on receipt. |
| `ExchangedDocument/ID` | A new random UUID per document. |
| `ExchangedDocument/TypeCode` | `019`. |
| `ExchangedDocument/IssueDateTime/udt:DateTimeString` | Time of creation, `format="207"` (`yyyyMMddHHmmss` + zone). |
| `ExchangedDocument/RequestedSpecifiedQuery/ID` | The query id. The gate sets it to the `X-Request-Id` of the xml-mapper call, which is also the AS4 `ConversationId`. |
| `ExchangedDocument/RequesterTradeParty/PostalTradeAddress/CountryID` | Optional, requester country. The gate does not write it and ignores it on receipt. |
| `EFTIIDInformation/ParameterSearchCriteria` | The search criteria, one `…ParameterScope` element per criterion, each with a `SubtypeCode` (operator) and a value. All criteria must match (AND). |

The criteria elements and their JSON names:

| XML element | JSON field | Value element | Operators |
|---|---|---|---|
| `CarrierAcceptanceDateParameterScope` (0-2) | `acceptanceDate[]` | `SpecifiedDateTime/udt:DateTimeString` | `EQ NE LT LE GT GE` |
| `CarrierAcceptanceCountryParameterScope` | `acceptanceCountry` | `CountryID` | `EQ NE` |
| `DeliveryDateParameterScope` (0-2) | `deliveryDate[]` | `SpecifiedDateTime/udt:DateTimeString` | `EQ NE LT LE GT GE` |
| `DeliveryCountryParameterScope` | `deliveryCountry` | `CountryID` | `EQ NE` |
| `DangerousGoodsIndicationCodeParameterScope` | `dangerousGoodsCode` | `DangerousGoodsIndicationParameterCode` | `EQ NE` |
| `MainCarriageTransportMeansIDParameterScope` | `mainTransportId` | `ID` | `EQ NE` |
| `MainCarriageModeCodeParameterScope` | `transportMode` | `TransportModeParameterCode` | `EQ NE` |
| `MainCarriageTransportMeansTypeCodeParameterScope` | `mainTransportType` | `TransportMeansParameterCode` | `EQ NE` |
| `TransportMeansRegistrationCountryParameterScope` | `transportRegCountry` | `CountryID` | `EQ NE` |
| `MainCarriageLoadingDateParameterScope` (0-2) | `loadingDate[]` | `SpecifiedDateTime/udt:DateTimeString` | `EQ NE LT LE GT GE` |
| `MainCarriageLoadingCountryParameterScope` | `loadingCountry` | `CountryID` | `EQ NE` |
| `MainCarriageUnloadingDateParameterScope` (0-2) | `unloadingDate[]` | `SpecifiedDateTime/udt:DateTimeString` | `EQ NE LT LE GT GE` |
| `MainCarriageUnloadingCountryParameterScope` | `unloadingCountry` | `CountryID` | `EQ NE` |
| `UsedTransportEquipmentIDParameterScope` | `usedEquipmentId` | `ID` | `EQ NE` |
| `UsedTransportEquipmentCategoryCodeParameterScope` | `usedEquipmentCategory` | `TransportEquipmentCategoryParameterCode` | `EQ NE` |
| `UsedTransportEquipmentRegistrationCountryParameterScope` | `usedEquipmentCountry` | `CountryID` | `EQ NE` |
| `UsedTransportEquipmentSequenceNumberParameterScope` | `usedEquipmentSeq` | `SequenceNumeric` | `EQ NE` |
| `CarriedTransportEquipmentIDParameterScope` | `carriedEquipmentId` | `ID` | `EQ NE` |
| `CarriedTransportEquipmentCategoryCodeParameterScope` | `carriedEquipmentCategory` | `TransportEquipmentCategoryParameterCode` | `EQ NE` |
| `CarriedTransportEquipmentSequenceNumberParameterScope` | `carriedEquipmentSeq` | `SequenceNumeric` | `EQ NE` |

A missing `SubtypeCode` means `EQ`. The JSON-only criterion `transportMeansOrEquipmentId` (one
identifier in main transport, used or carried equipment) has no FTI019 element: when the gate
sends it to a peer it becomes `MainCarriageTransportMeansIDParameterScope`, so equipment ids are
matched only by the gate's own register. What each criterion matches in the register is in
[Search criteria](../../data/search-criteria.md).

Two criteria give, for example (G1, `{"mainTransportId":{"id":"TST-001"},"deliveryCountry":{"country":"LV"}}`):

```xml
<rsm:EFTIIDInformation><ParameterSearchCriteria><DeliveryCountryParameterScope><SubtypeCode>EQ</SubtypeCode><CountryID>LV</CountryID></DeliveryCountryParameterScope><MainCarriageTransportMeansIDParameterScope><SubtypeCode>EQ</SubtypeCode><ID>TST-001</ID></MainCarriageTransportMeansIDParameterScope></ParameterSearchCriteria></rsm:EFTIIDInformation>
```

The elements are always written in the order of the table above.

## FTI021SearchIdentifierResponse

G2's answer to the request above, as returned by G1's send API (indentation added):

```xml
<rsm:FTI021SearchIdentifierResponse xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI021SearchIdentifierResponse:1" xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34" xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
  <rsm:ExchangedDocumentContext>
    <MessageStandardSpecifiedDocumentContextParameter>
      <ID>eFTI</ID>
      <SpecifiedDocumentVersion>
        <ID>1.0</ID>
      </SpecifiedDocumentVersion>
    </MessageStandardSpecifiedDocumentContextParameter>
  </rsm:ExchangedDocumentContext>
  <rsm:ExchangedDocument>
    <ID schemeID="RFC 9562-4">1dc7d177-cd49-4168-acc9-d70eb741d9c9</ID>
    <TypeCode>021</TypeCode>
    <StatusCode>C</StatusCode>
    <IssueDateTime>
      <udt:DateTimeString format="207">20261103155957Z</udt:DateTimeString>
    </IssueDateTime>
    <RequestedSpecifiedQuery>
      <ID schemeID="RFC 9562-4">78ce1c60-05ca-4708-a022-7504a9f82a05</ID>
    </RequestedSpecifiedQuery>
  </rsm:ExchangedDocument>
  <rsm:EFTIIDInformation>
    <UniqueIDSetUniqueIDSet>
      <GateID>G2</GateID>
      <PlatformID>mock</PlatformID>
      <DatasetID schemeID="RFC 9562-4">22222222-0000-4000-8000-000000000001</DatasetID>
      <ParameterIDSetCriteria>
        <MainCarriageTransportMeansIDParameterScope>
          <ID>G2-AB123</ID>
        </MainCarriageTransportMeansIDParameterScope>
      </ParameterIDSetCriteria>
    </UniqueIDSetUniqueIDSet>
  </rsm:EFTIIDInformation>
</rsm:FTI021SearchIdentifierResponse>
```

| Element | Meaning |
|---|---|
| `ExchangedDocument/TypeCode` | `021`. |
| `ExchangedDocument/StatusCode` | `C` (Completed). The gate always writes `C`, also for an empty result. Other codes in the model: `E` Error, `P` Pending, `T` Terminated, `U` Unauthorized. |
| `ExchangedDocument/IncludedNote` | Only when nothing matched, see below. |
| `ExchangedDocument/RequestedSpecifiedQuery/ID` | The request id the answering gate's handler ran under, which is the AS4 `ConversationId` of the request. |
| `EFTIIDInformation/UniqueIDSetUniqueIDSet` | One per matching consignment, newest first, at most 20. |
| `…/GateID`, `PlatformID`, `DatasetID` | The [UIL](../../data/uil.md) of the dataset. |
| `…/ParameterIDSetCriteria` | The identifiers the platform registered for this dataset (stored as uploaded in FTI004), not the criteria of the search. |

### No consignments found

When nothing matches, the answer has no `UniqueIDSetUniqueIDSet` and an `IncludedNote` with
`ContentCode` `404`. This is G2's answer to a search for `TST-002`, which only G1 has:

```bash
RID=$(cat /proc/sys/kernel/random/uuid)
T='X-Internal-Service-Token: dev-internal-service-token-change-me'
curl -s http://127.0.0.1:27082/api/v1/search/request-to-xml -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: application/json' -d '{"mainTransportId":{"id":"TST-002","operator":"EQ"}}' > fti019-empty.xml
curl -s -i http://127.0.0.1:27081/api/v1/send/G2 -H "$T" -H "X-Request-Id: $RID" \
  -H 'Content-Type: text/xml' --data-binary @fti019-empty.xml
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 1121

<rsm:FTI021SearchIdentifierResponse xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI021SearchIdentifierResponse:1" xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34" xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34"><rsm:ExchangedDocumentContext><MessageStandardSpecifiedDocumentContextParameter><ID>eFTI</ID><SpecifiedDocumentVersion><ID>1.0</ID></SpecifiedDocumentVersion></MessageStandardSpecifiedDocumentContextParameter></rsm:ExchangedDocumentContext><rsm:ExchangedDocument><ID schemeID="RFC 9562-4">654c67a7-df49-46e6-a4e4-26c271ee2fdb</ID><TypeCode>021</TypeCode><StatusCode>C</StatusCode><IssueDateTime><udt:DateTimeString format="207">20261103160016Z</udt:DateTimeString></IssueDateTime><IncludedNote><IncludedNote><ContentCode>404</ContentCode><Content languageID="en">No consignments found</Content></IncludedNote></IncludedNote><RequestedSpecifiedQuery><ID schemeID="RFC 9562-4">ccffc8c8-4b13-47c9-a95a-70427afded69</ID></RequestedSpecifiedQuery></rsm:ExchangedDocument>
    <rsm:EFTIIDInformation></rsm:EFTIIDInformation>
  </rsm:FTI021SearchIdentifierResponse>
```

Note the nested `<IncludedNote><IncludedNote>…</IncludedNote></IncludedNote>`: `ExchangedDocument.render()`
wraps the output of `IncludedNote.render()`, which already writes its own `IncludedNote` element.
The schema has one level. The gate's own fan-out does not read the note (it keeps only answers
that contain `ParameterIDSetCriteria`), so this does not break gate-to-gate searches between two
of these gates, but a stricter peer may reject the document.

## How the receiving gate handles FTI019

1. edelivery accepts and receipts the AS4 message, sees the root `FTI019SearchIdentifierRequest`
   and calls `POST <RUUTER_URL>/efti/api/v1/consignments/search-xml?gateId=<own gate id>` with the
   XML, `x-request-id` = the `ConversationId` and the internal service token.
2. Ruuter converts the XML to criteria JSON (xml-mapper `/api/v1/search/request-to-json`).
3. ReSql `get_consignments` searches the gate's own register: rows of this gate (`gate_id` =
   `gateId`), latest version of each dataset, not `DELETED`, all criteria ANDed, newest first,
   limit 20.
4. xml-mapper `/api/v1/search/response-to-xml` builds the FTI021.
5. edelivery sends it to the requesting gate's `eDeliveryUrl` with the same `ConversationId`.

The requesting gate releases the waiting send call with that XML. Details, status codes and direct
calls: [Inbound handlers](./inbound-handlers.md). The end-to-end run with log lines:
[A peer gate asks this gate](../../use-cases/peer-gate-request.md).

## Errors

| Case | What the requester sees |
|---|---|
| No match | `200`, FTI021 with the `No consignments found` note |
| The XML has no `ParameterSearchCriteria` content | `200`, FTI021 with up to 20 rows of the peer (no filter) |
| The XML does not parse (observed on a direct handler call with `<nope>`) | `200`, FTI021 with the `No consignments found` note |
| Peer's database (ReSql) down | The handler answers `502` `{"error": "Data layer unavailable"}`; edelivery drops it; the requester gets `504` after 60 s (not run locally: it needs a stopped ReSql) |
| Peer rejects the AS4 message | `500` from the send API with the ebMS reason ([send API](./send-api.md)) |

## Notes

- The search is limited to 20 rows per peer. FTI019 has no paging, and the gate does not send a
  limit.
- `RequestedSpecifiedQuery/ID` of the answer is the `ConversationId`, not the query id read from
  the FTI019. For two of these gates they are the same value. A peer that uses different values for
  the two gets its `ConversationId` back.
- The fan-out uses one request id for all peers; the waiter keys differ by party id.
- Postman: **2. G2G -> peer gate / FTI019 search TST-001**; run it with each gate's environment for both directions.
