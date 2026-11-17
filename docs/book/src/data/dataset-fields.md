# Dataset content (FTI010)

What an authority gets back from [dataset](../api/authorities/dataset.md): the consignment data
of one UIL, as the platform sent it in FTI010 (`SpecifiedSupplyChainConsignment`), once as XML and
once as JSON. This page explains both forms and lists the fields.

The X-Road clients below are development fixtures, public in the repo.

## The answer

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

Body pretty-printed:

```http
HTTP/1.1 200 OK
content-type: application/json
traceparent: 00-9ca5e19f88e82499315bac1ea826aa74-28972af013f1efc9-01
x-trace-id: 9ca5e19f88e82499315bac1ea826aa74
content-length: 1133
{
  "consignment": {
    "grossWeightMeasure": "15000.00",
    "grossWeightMeasure@unitCode": "KGM",
    "mainCarriageLogisticsTransportMovement": {
      "modeCode": "3",
      "usedLogisticsTransportMeans": {
        "Id": "MOCK-PLATE-1",
        "Id@schemeAgencyId": "5",
        "registrationTradeCountry": {
          "Id": "EE"
        }
      }
    },
    "packageQuantity": "120"
  },
  "subsets": [
    "EU01"
  ],
  "uil": {
    "datasetId": "11111111-0000-4000-8000-000000000001",
    "gateId": "G1",
    "platformId": "mock"
  },
  "xml": "<SpecifiedSupplyChainConsignment\n    xmlns:rsm=\"urn:eu:move:eFTI:data:standard:FTI010GetCmdsResponse:1\"\n    xmlns=\"urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34\"\n    xmlns:udt=\"urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34\">\n  <GrossWeightMeasure unitCode=\"KGM\">15000.00</GrossWeightMeasure>\n  <PackageQuantity>120</PackageQuantity>\n  <MainCarriageLogisticsTransportMovement>\n    <ModeCode>3</ModeCode>\n    <UsedLogisticsTransportMeans>\n      <ID schemeAgencyID=\"5\">MOCK-PLATE-1</ID>\n      <RegistrationTradeCountry><ID>EE</ID></RegistrationTradeCountry>\n    </UsedLogisticsTransportMeans>\n  </MainCarriageLogisticsTransportMovement>\n</SpecifiedSupplyChainConsignment>"
}
```

The `xml` value, unescaped:

```xml
<SpecifiedSupplyChainConsignment
    xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI010GetCmdsResponse:1"
    xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34"
    xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
  <GrossWeightMeasure unitCode="KGM">15000.00</GrossWeightMeasure>
  <PackageQuantity>120</PackageQuantity>
  <MainCarriageLogisticsTransportMovement>
    <ModeCode>3</ModeCode>
    <UsedLogisticsTransportMeans>
      <ID schemeAgencyID="5">MOCK-PLATE-1</ID>
      <RegistrationTradeCountry><ID>EE</ID></RegistrationTradeCountry>
    </UsedLogisticsTransportMeans>
  </MainCarriageLogisticsTransportMovement>
</SpecifiedSupplyChainConsignment>
```

The `mock` platform serves this minimal fixture for every dataset id except a few
(`DSL/Ruuter/mock-platform/GET/v1/dataset.yml`); that is why the plate is `MOCK-PLATE-1` and not
the seeded `TST-001`.

| Field | Type | Content |
|---|---|---|
| `uil` | object | the UIL from the request, unchanged ([UIL](./uil.md)) |
| `subsets` | array | the subsets from the request, unchanged ([eFTI subsets](./subsets.md)) |
| `xml` | string | the `SpecifiedSupplyChainConsignment` element of the FTI010 |
| `consignment` | object | the same element as JSON, made by xml-mapper (`POST /api/v1/dataset/response-to-json`) |

## The `xml` field

xml-mapper cuts the `SpecifiedSupplyChainConsignment` element out of whatever the platform or the
peer gate sent (`extractFTITag` in `code/xml-mapper/src/efti/xml/fti/FTIMessages.kt`):

- every namespace prefix is removed from the tags except `udt:`, so `rsm:SpecifiedSupplyChainConsignment`
  becomes `SpecifiedSupplyChainConsignment`;
- if `udt` was declared higher up, the root gets `xmlns:udt=…` added;
- the namespace declarations that were on the element itself stay.

The two routes therefore give different roots:

| UIL at | Root of `xml` |
|---|---|
| this gate (REST platform) | the platform's own declarations, here `xmlns:rsm`, default `xmlns` (ReusableAggregateBusinessInformationEntity:34), `xmlns:udt` |
| another gate (FTI010 over AS4) | `<SpecifiedSupplyChainConsignment xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">` and nothing else |

The content model is the eFTI XM, in `code/xml-mapper/xsd/FTI010/` and
`code/xml-mapper/xsd/eFTI XM/`.

## The `consignment` object

xml-mapper turns the element into JSON with fixed rules:

| XML | JSON | Example |
|---|---|---|
| element name | first letter lower case | `GrossWeightMeasure` → `grossWeightMeasure` |
| `ID` anywhere in a name | `Id` (the rule replaces every `id`, also inside words) | `ID` → `Id`, `URIID` → `uRIId`, `ConsignorProvidedInformation` → `consignorProvIdedInformation` |
| attribute | sibling key `element@attribute`, same name rules | `<ID schemeAgencyID="5">` → `"Id@schemeAgencyId": "5"` |
| element with children | object | `mainCarriageLogisticsTransportMovement: {…}` |
| element that repeats | array | `includedSupplyChainConsignmentItem: [{…}, {…}]` |
| text | string, always, also numbers and booleans | `"packageQuantity": "120"`, `"transshipmentPermissionIndicator": "true"` |
| `udt:DateTimeString` | `dateTimeString` and `dateTimeString@format`, value not converted | `{"dateTimeString":"202609140730+0300","dateTimeString@format":"205"}` |
| namespaces, element order | dropped | |

Keys are sorted by code point (`cODAmount` before `cargoInsurance…`). Use `xml` if you need the
document order or the namespaces; use `consignment` to read values.

## A full dataset

The rich fixture `EU-EE/mock/550e8400-e29b-41d4-a716-446655440002` fills every FTI010 field.
Both the gate's mock platform and the [developer mock](../api/authorities/developer-mock.md) serve
it.

```bash
curl -s -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"EU-EE","platformId":"mock","datasetId":"550e8400-e29b-41d4-a716-446655440002"},"subsets":["EU01"]}'
```

The answer is 61 789 bytes; `xml` has 36 560 characters and `consignment` 49 top-level keys:

```text
applicableCrossBorderRegulatoryProcedure  applicableLogisticsServiceCharge  applicableTransportDangerousGoods
associatedReferencedDocument  associatedTradeParty  cODAmount  cODAmount@currencyId
cargoInsuranceInstructionsInformation  carrierAcceptanceDateTime  carrierAcceptanceLogisticsLocation
carrierTradeParty  connectingCarrierTradeParty  consigneeReceiptLogisticsLocation  consigneeTradeParty
consignorProvIdedBorderClearanceTransportInstructions  consignorProvIdedInformation  consignorTradeParty
contractTermsInformation  declaredValueForCarriageAmount  declaredValueForCarriageAmount@currencyId
deliveryInformation  deliveryTransportEvent  freightForwarderTradeParty  grossVolumeMeasure
grossVolumeMeasure@unitCode  grossWeightMeasure  grossWeightMeasure@unitCode
includedSupplyChainConsignmentItem  information  mainCarriageLogisticsTransportMovement
natureIdentificationTransportCargo  netWeightMeasure  netWeightMeasure@unitCode
notifiedTransportationWasteMaterial  onCarriageLogisticsTransportMovement  packageQuantity
paymentArrangementCode  paymentArrangementCode@listAgencyId  preCarriageLogisticsTransportMovement
specifiedLogisticsRiskAnalysisResult  specifiedLogisticsTransportMovement
transportContractReferencedDocument  transportEquipmentQuantity  transportEvent
transportLogisticsPackage  transportService  transshipmentLogisticsLocation
transshipmentPermissionIndicator  utilizedLogisticsTransportEquipment
```

Two parts of it:

```json
"carrierAcceptanceDateTime": {"dateTimeString": "202609140730+0300", "dateTimeString@format": "205"},
"consignorTradeParty": {
  "Id": "MOCK-EE-1001",
  "Id@schemeAgencyId": "87",
  "confirmedDocumentAuthentication": {"statementCode": "1"},
  "definedTradeContact": {
    "emailURIUniversalCommunication": {"uRIId": "saatmine@mock-varvitehas.example"},
    "telephoneUniversalCommunication": {"completeNumber": "+372 5550 1001"}
  },
  "name": "Mock Värvitehas AS",
  "ownedCreditorFinancialAccount": {"proprietaryId": "MOCK-ACC-1001", "proprietaryId@schemeAgencyId": "3"},
  "postalTradeAddress": {
    "additionalStreetName": "korpus 3", "buildingNumber": "46", "cityName": "Tallinn",
    "countryId": "EE", "countrySubDivisionName": "Harju maakond", "departmentName": "Laoosakond",
    "postOfficeBox": "PK 101", "postcodeCode": "11415", "streetName": "Peterburi tee"
  },
  "specifiedContactPerson": {"familyName": "Maasikas", "givenName": "Mari"},
  "specifiedTaxRegistration": {"Id": "MOCK-VAT-EE-1001", "Id@schemeAgencyId": "97"}
}
```

The same request with `"subsets":["EU01","EU02"]` returns the same content. The mock platform does
not apply subsets; a real platform must.

## Field catalogue

`docs/developer/fti010-field-catalogue.md` lists all 563 fields of the rich fixture with XPath,
`consignment` key, eFTI ID, business name, code list and sample value. It is generated by
`scripts/sync-mock-datasets.py` and written in Estonian. Full file:
[fti010-field-catalogue.md on GitHub](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/developer/fti010-field-catalogue.md).

Condensed, one row per child of `SpecifiedSupplyChainConsignment`, in document order:

| XML element | `consignment` key | Fields | eFTI IDs (first, last) | Contains |
|---|---|---|---|---|
| `CarrierAcceptanceDateTime` | `carrierAcceptanceDateTime` | 2 | eFTI39, eFTI40 | when the carrier took the goods (`DateTimeString`, format) |
| `GrossWeightMeasure` | `grossWeightMeasure` | 2 | eFTI41, eFTI42 | gross mass, unit (CL-031) |
| `NetWeightMeasure` | `netWeightMeasure` | 2 | eFTI43, eFTI44 | net mass, unit |
| `GrossVolumeMeasure` | `grossVolumeMeasure` | 2 | eFTI45, eFTI46 | gross volume, unit |
| `CODAmount` | `cODAmount` | 2 | eFTI854, eFTI855 | cash on delivery, currency (CL-018) |
| `PackageQuantity` | `packageQuantity` | 1 | eFTI47 | number of packages |
| `TransportEquipmentQuantity` | `transportEquipmentQuantity` | 1 | eFTI1146 | number of equipment units |
| `Information` | `information` | 1 | eFTI48 | free text |
| `DeclaredValueForCarriageAmount` | `declaredValueForCarriageAmount` | 2 | eFTI1149, eFTI1150 | declared value, currency |
| `PaymentArrangementCode` | `paymentArrangementCode` | 2 | eFTI1307, eFTI1308 | payment arrangement (CL-030) |
| `DeliveryInformation` | `deliveryInformation` | 1 | eFTI1151 | delivery instructions |
| `CargoInsuranceInstructionsInformation` | `cargoInsuranceInstructionsInformation` | 1 | eFTI856 | insurance instructions |
| `TransshipmentPermissionIndicator` | `transshipmentPermissionIndicator` | 1 | eFTI857 | transshipment allowed (CL-025) |
| `ConsignorProvidedInformation` | `consignorProvIdedInformation` | 1 | eFTI858 | consignor's notes |
| `ContractTermsInformation` | `contractTermsInformation` | 1 | eFTI1309 | contract terms |
| `ConsignorTradeParty` | `consignorTradeParty` | 21 | eFTI49, eFTI65 | consignor: id, name, contact, address, tax id, contact person, bank account, authentication |
| `ConsigneeTradeParty` | `consigneeTradeParty` | 20 | eFTI66, eFTI84 | consignee: same structure |
| `CarrierTradeParty` | `carrierTradeParty` | 28 | eFTI85, eFTI115 | carrier: as above plus role, contract, licence |
| `FreightForwarderTradeParty` | `freightForwarderTradeParty` | 10 | eFTI1310, eFTI1324 | freight forwarder: name, address |
| `ConnectingCarrierTradeParty` | `connectingCarrierTradeParty` | 15 | eFTI116, eFTI1176 | successive carrier: id, name, address, licence |
| `CarrierAcceptanceLogisticsLocation` | `carrierAcceptanceLogisticsLocation` | 12 | eFTI136, eFTI151 | place of taking over: id, name, coordinates, address |
| `TransshipmentLogisticsLocation` | `transshipmentLogisticsLocation` | 3 | eFTI1326, eFTI1328 | transshipment place |
| `ConsigneeReceiptLogisticsLocation` | `consigneeReceiptLogisticsLocation` | 12 | eFTI152, eFTI167 | place of delivery |
| `TransportContractReferencedDocument` | `transportContractReferencedDocument` | 8 | eFTI168, eFTI1177 | transport document (e.g. CMR): id, type, URI, issue date and place, clauses |
| `AssociatedReferencedDocument` | `associatedReferencedDocument` | 9 | eFTI177, eFTI187 | other documents, with issuer and attached file |
| `DeliveryTransportEvent` | `deliveryTransportEvent` | 19 | eFTI188, eFTI1202 | delivery: actual, scheduled, requested times, place, observations |
| `TransportEvent` | `transportEvent` | 6 | eFTI195, eFTI219 | certifying party of an event |
| `IncludedSupplyChainConsignmentItem` | `includedSupplyChainConsignmentItem[]` | 83 | eFTI228, eFTI347 | goods items: quantity, volume, dimensions, dangerous goods, equipment link |
| `UtilizedLogisticsTransportEquipment` | `utilizedLogisticsTransportEquipment` | 127 | eFTI374, eFTI578 | equipment (trailer, container): id, category, seals, weights, carried equipment, dangerous goods |
| `MainCarriageLogisticsTransportMovement` | `mainCarriageLogisticsTransportMovement` | 84 | eFTI581, eFTI1270 | main carriage: mode, trip id, driver/master, loading, unloading, departure, transport means, route, calls, border crossing |
| `PreCarriageLogisticsTransportMovement` | `preCarriageLogisticsTransportMovement` | 3 | eFTI631, eFTI1357 | pre-carriage: mode, arrival |
| `OnCarriageLogisticsTransportMovement` | `onCarriageLogisticsTransportMovement` | 4 | eFTI658, eFTI673 | on-carriage: mode, unloading |
| `SpecifiedLogisticsTransportMovement` | `specifiedLogisticsTransportMovement` | 5 | eFTI1135, eFTI1139 | other movements and their events |
| `TransportService` | `transportService` | 3 | eFTI1277, eFTI942 | service description, conditions |
| `ApplicableLogisticsServiceCharge` | `applicableLogisticsServiceCharge` | 14 | eFTI943, eFTI1372 | charges: type, payment, amount, payer, basis |
| `TransportLogisticsPackage` | `transportLogisticsPackage` | 7 | eFTI685, eFTI1897 | packages: quantity, type, marks |
| `AssociatedTradeParty` | `associatedTradeParty` | 2 | eFTI692, eFTI693 | another party, by id |
| `NatureIdentificationTransportCargo` | `natureIdentificationTransportCargo` | 6 | eFTI698, eFTI702 | nature of the goods, statistical classification |
| `ApplicableCrossBorderRegulatoryProcedure` | `applicableCrossBorderRegulatoryProcedure` | 7 | eFTI703, eFTI1387 | customs procedure and offices (import, export, transit) |
| `ApplicableTransportDangerousGoods` | `applicableTransportDangerousGoods` | 20 | eFTI712, eFTI1752 | consignment-level dangerous goods: radioactive material, conditions, documents, exemptions |
| `ConsignorProvidedBorderClearanceTransportInstructions` | `consignorProvIdedBorderClearanceTransportInstructions` | 1 | eFTI965 | border clearance instructions |
| `SpecifiedLogisticsRiskAnalysisResult` | `specifiedLogisticsRiskAnalysisResult` | 8 | eFTI844, eFTI1902 | security screening result |
| `NotifiedTransportationWasteMaterial` | `notifiedTransportationWasteMaterial` | 4 | eFTI1745, eFTI1495 | waste: code, weight |

"Fields" counts leaves and attributes, so `GrossWeightMeasure` with `@unitCode` counts 2. The
catalogue does not say which subset a field belongs to.

## Notes

- The gate does not validate the dataset against the XSD and does not remove fields. What the
  platform returns is what the authority gets.
- The `id` → `Id` rule also changes letters inside words (`ProvIded`, `uRIId`, `cODAmount`).
  These are the real keys; match them exactly.
- Every scalar is a string. Convert numbers, booleans and dates yourself; the `@format` key gives
  the date format ([Dates and times](../api/conventions.md#dates-and-times)).
- From another gate, the `xml` root declares only `udt`. The default namespace
  (`…ReusableAggregateBusinessInformationEntity:34`) is not declared on the fragment, so a
  namespace-aware parser sees the elements in no namespace.
- Related: [dataset](../api/authorities/dataset.md), [Dataset: FTI009 / FTI010](../api/gates/dataset-fti009.md),
  [What the gate calls on a platform](../api/platforms/platform-side-api.md).
