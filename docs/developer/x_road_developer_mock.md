# X-tee arendaja-mock (`eftidev.kemitaws.ee/developer/`)

Avalik liivakast, mis matkib eFTI värava X-tee pakkuja-liidest
([`x_road_authority_integration_guide.md`](x_road_authority_integration_guide.md),
[`x_road_openapi.yaml`](x_road_openapi.yaml)) **konserveeritud vastustega**. Mõeldud selleks, et
integreerija saaks oma kliendi valmis ehitada ja veakäsitluse läbi mängida **enne**, kui X-tee
juurdepääs, turvaserver ja asutuse registreering on paigas.

## Miks eraldi teenus

Mock jookseb **omaette konteinerina** (`ruuter-xroad-mock`, port 8088) — eraldi Ruuteri projekt,
millel **ei ole** andmebaasi, `core`-i ega ühtki ühendust päris väravaga. Iga marsruut vastab
literaali pealt. Isegi vigane marsruut ei saa
lekitada päris saadetiste andmeid ega jõuda privilegeeritud teeni. Päris `/xroad/**` **ei ole**
kunagi avalikult marsruuditav (ADR-006) — mock on see, mille vastu arendaja testib.

### Mock ja live on 1:1

1:1 tähendab, et mock ja päris värav kasutavad **sama lepingut**: päringud on samad ja vastused sama
kujuga. Andmed on erinevad. Mockil puudub andmebaas ja see vastab ainult oma DSL-failides olevate
näidisandmetega. Päris värav neid näidisandmeid ei tunne, nii et `MOCK-PLATE-3` otsing annab seal
tühja tulemuse.

`dataset` vastus on mõlemas kujul `{uil, subsets, xml, consignment}`. Mocki `xml` ja `consignment` on
eelarvutatud samade reeglitega, mida värava xml-mapper (`/api/v1/dataset/response-to-json`) kasutab.
Värava arenduskeskkonna `mock`-platvorm (`DSL/Ruuter/mock-platform/GET/v1/dataset.yml`) serveerib
sama dataset'i XML-i nagu mock. Nii saab samale UIL-ile tehtud dataset-päringuga kontrollida, et
vastused on baidi täpsusega samad.

Mõlemad dataset'i failid genereerib `python3 scripts/sync-mock-datasets.py` failist
`scripts/generate-rich-mock-dataset.py`, nii et käsitsi neid ei muudeta. `tests/mock/dataset.py`
kontrollib, et failid on ajakohased ning mocki `xml`/`consignment` vastab xml-mapperi reeglitele.
Käivitatud mocki vastu: `MOCK_TEST_URL=http://localhost:8088 python3 tests/mock/dataset.py`.

`search` on samuti sama lepinguga: mõlemad tagastavad sama kureeritud projektsiooni nagu
`transport-means` (`uil`, `mainTransportId`, …, ilma `xml`-ita) ja sama `x-poll-more` käitumise. Mock
filtreerib oma nelja näidisrida samade kriteeriumireeglitega nagu värava `get_consignments.sql` ja
samas järjekorras (uuemad ees).

```
Arendaja klient ──HTTP──> https://eftidev.kemitaws.ee/developer/v1/{operatsioon}
                          (nginx → xtee-mock-teenus, konserveeritud vastus)
```

## Baas-URL ja teed

| | |
|---|---|
| Avalik baas-URL | `https://eftidev.kemitaws.ee/developer/` |
| Tervisekontroll | `GET /developer/health/ready` → `200 "OK"` (päiseid ei vaja) |


Vaste päris väravale: mock `/developer/v1/echo` ↔ värav `/xroad/v1/echo` ↔ tarbija
`/r1/EE/GOV/70001231/efti-gate/echo/v1`.

## Päised

Samad mis päris liideses. Mockis paned need **sina ise** (päris elus paneb turvaserver).

| Päis | Kohustuslik | Mock-kontroll |
|---|---|---|
| `X-Road-Client` | jah | `instance/memberClass/memberCode[/subsystemCode]` — 3- või 4-osaline. Puudub/vigane → `401 UNAUTHORIZED`. |
| `X-Road-Id` | jah | Peab olema UUID-kujuline (8-4-4-4-12). Puudub → `400 MISSING_REQUIRED_HEADER`; vale kuju → `400 INVALID_REQUEST_ID`. |
| `X-Road-UserId` | ei | Kajastatakse `echo`-s tagasi; õigusi ei anna. |

## Erilised registrikoodid (`X-Road-Client` `memberCode`, index 2)

| `memberCode` | Käitumine |
|---|---|
| `00000000` | Guard → `403 FORBIDDEN` (matkib "ei ole registreeritud asutus"). |
| `70000000` | Laheneb, aga **õigusi ei ole** — `subsets: []`; `dataset` ja `transport-means` → `403 FORBIDDEN_SUBSET`. |
| kõik muu korrektne | Laheneb mock-asutuseks `auth-mock`, `subsets: ["EU01","EU02","EU03","EU05"]`. `registryCode` võrdub sinu `memberCode`-ga. |

## Konserveeritud andmed

| Väli | Väärtus |
|---|---|
| Mock-asutus | `id: "auth-mock"`, `name: "Mock Competent Authority"`, `status: "ACTIVE"` |
| Lubatud alamhulgad | `["EU01","EU02","EU03","EU05"]` (v.a `memberCode 70000000` → `[]`) |
| Tuntud tunnused | `MOCK-PLATE-1` (seadme nr `CONT-MOCK-01`) → 1 saadetis; `MOCK-PLATE-3`, haagis `MOCK-TRAILER-3`, konteiner `MOCK-CONTAINER-3` → 3 saadetist; kõik muu → tühi tulemus |
| Tuntud `uil` | `{ "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440001" }` |
| Rikkalik dataset | `{ "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440002" }` — täieliku väljakattuvusega sünteetiline FTI010 näidis |

## Maksimaalse tulemusega päringud

Need kaks päringut annavad mockist kõige suurema vastuse. Esimene sisaldab kõiki välju, teine kõiki
vormi välju sisuliste väärtustega. Mõlemas küsitakse kõiki alamhulki, mis mock-asutusele on lubatud
(`GET /developer/v1/subsets` → `["EU01","EU02","EU03","EU05"]`). Mock XML-i alamhulkade järgi ei
filtreeri, aga päris väravas annab ainult lubatud alamhulkade küsimine täieliku vastuse. Lubamata
alamhulga lisamine ei laienda vastust, vaid annab `403 FORBIDDEN_SUBSET`. Päris väravas küsi seega
täpselt need alamhulgad, mida sinu asutuse `subsets` tagastab.

**1. Kõik FTI010 väljad** — UIL `...0002`. Vastuses on iga FTI010 `SpecifiedSupplyChainConsignment`
element üks kord; kus skeem lubab ainult ühte variandist (`choice`), on kasutatud esimest. See on
parim päring, et kogu struktuur ühe korraga ära kaardistada.

```http
POST https://eftidev.kemitaws.ee/developer/v1/dataset
X-Road-Client: EE/GOV/70000097/test
X-Road-Id: 550e8400-e29b-41d4-a716-446655440099
Content-Type: application/json

{
  "uil": { "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440002" },
  "subsets": ["EU01", "EU02", "EU03", "EU05"]
}
```

**2. Kõik vormi väljad sisuliste väärtustega** — UIL `...0013`. Ohtlik veos, kus on veok, haagis,
konteiner, CMR, kauba kirjeldus, maht, saatja, saaja, vedaja, juht, märkused ning ohtliku kauba
andmed (UN, klass, pakendirühm, pakend, tunnel). Väljade asukohad on tabelis „Vormi väljad: kust need
dataset'i vastusest leiab”.

```http
POST https://eftidev.kemitaws.ee/developer/v1/dataset
X-Road-Client: EE/GOV/70000097/test
X-Road-Id: 550e8400-e29b-41d4-a716-446655440098
Content-Type: application/json

{
  "uil": { "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440013" },
  "subsets": ["EU01", "EU02", "EU03", "EU05"]
}
```

Otsingus annab suurima tulemuse `MOCK-PLATE-3`: `transport-means` päring kehaga
`{"identifier":"MOCK-PLATE-3","scope":"local"}` tagastab kõik kolm saadetist (`found: 3`) koos
haagise ja konteineri andmetega. Iga `X-Road-Id` peab olema unikaalne UUID; ülaltoodud väärtused on
näited.

---

## Operatsioonid

Vastuse `Content-Type` on `application/json`. Vead on RFC 7807 kujuga
(`type`/`code`/`title`/`status`/`detail` + operatsioonispetsiifilised väljad).

### 1. `echo` — ühenduse test

**Päring**
```
POST /developer/v1/echo
X-Road-Client: EE/GOV/70000097/tram
X-Road-Id: 4894e35d-bf0f-44a6-867a-8e51f1daa7e0
Content-Type: application/json

{ "hello": "world" }
```

**Vastus `200`**
```json
{
  "xRoadClient": "EE/GOV/70000097/tram",
  "xRoadId": "4894e35d-bf0f-44a6-867a-8e51f1daa7e0",
  "xRoadUserId": null,
  "authority": {
    "id": "auth-mock",
    "registryCode": "70000097",
    "name": "Mock Competent Authority",
    "subsets": ["EU01", "EU02", "EU03", "EU05"],
    "status": "ACTIVE"
  },
  "echo": { "hello": "world" }
}
```

**Vead:** `401 UNAUTHORIZED`, `400 MISSING_REQUIRED_HEADER`, `400 INVALID_REQUEST_ID`,
`403 FORBIDDEN` (`memberCode 00000000`).

---

### 2. `subsets` — oma alamhulga-õigused

**Päring**
```
GET /developer/v1/subsets
X-Road-Client: EE/GOV/70000097/tram
X-Road-Id: 4894e35d-bf0f-44a6-867a-8e51f1daa7e0
```

**Vastus `200`**
```json
{ "registryCode": "70000097", "authorityId": "auth-mock", "subsets": ["EU01", "EU02", "EU03", "EU05"] }
```

`memberCode 70000000` → `"subsets": []`.

---

### 3. `transport-means` — veovahendi / veoseühiku otsing

**Päring**
```
POST /developer/v1/transport-means
X-Road-Client: EE/GOV/70000097/tram
X-Road-Id: 4894e35d-bf0f-44a6-867a-8e51f1daa7e0
Content-Type: application/json

{ "identifier": "MOCK-PLATE-1", "countryCode": "EE", "scope": "local" }
```

| Väli | Kohustuslik | Sisu |
|---|---|---|
| `identifier` | jah | mittetühi string: veoki, haagise või konteineri number (vt „Tuntud tunnused”) |
| `countryCode` | ei | veoki registreerimisriik; kõigi näidiste puhul `EE`, muu riik → `found: 0` (ka haagise/konteineri numbriga, nagu live SQL-is) |
| `scope` | ei | `existence` \| `local` (vaikimisi) \| `allgates` |

**Vastus `200` (`scope: local`, tabamus)**
```json
{
  "identifier": "MOCK-PLATE-1",
  "countryCode": "EE",
  "scope": "local",
  "found": 1,
  "consignments": [
    {
      "uil": { "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440001" },
      "mainTransportId": "MOCK-PLATE-1",
      "mainTransportType": "1522",
      "transportRegCountry": "EE",
      "transportMode": "3",
      "dangerousGoods": null,
      "loadingDate": "2026-04-23T06:00:00Z", "loadingCountry": "EE",
      "unloadingDate": "2026-04-25T14:00:00Z", "unloadingCountry": "FI",
      "usedEquipmentIds": ["CONT-MOCK-01"], "usedEquipmentCategories": ["CN"], "usedEquipmentCountries": ["EE"],
      "carriedEquipmentIds": [], "carriedEquipmentCategories": [],
      "status": "ACTIVE",
      "createdAt": "2026-04-23T10:15:00Z"
    }
  ]
}
```

Tundmatu tunnus → `"found": 0, "consignments": []`.

**Vastus `200` (`scope: existence`)**
```json
{ "identifier": "MOCK-PLATE-1", "countryCode": "", "scope": "existence", "registered": true }
```

**Vead**

| Staatus | `code` | Millal |
|---|---|---|
| 400 | `BAD_REQUEST_GENERAL` | `identifier` puudub/tühi; või `scope` tundmatu |
| 403 | `FORBIDDEN_SUBSET` | `memberCode 70000000` (pole EU02); keha: `authorityId`, `deniedSubsets: ["EU02"]`, `permittedSubsets: []` |
| 501 | `NOT_IMPLEMENTED` | `scope: allgates` — mock EI teosta fan-out'i ega pollimist (`{poll: true}`); päris värav teostab mõlemad (issue #125), nii et pollimistsüklit selle mocki vastu arendada ei saa |

---

### 4. `search` — saadetiste otsing

**Päring**
```
POST /developer/v1/search
X-Road-Client: EE/GOV/70000097/tram
X-Road-Id: 4894e35d-bf0f-44a6-867a-8e51f1daa7e0
Content-Type: application/json

{ "mainTransportId": { "operator": "EQ", "id": "MOCK-PLATE-1" } }
```

**Vastus `200`** — massiiv, mille iga element on sama projektsioon, mida `transport-means` tagastab
`consignments` all. Mock otsib nelja saadetise seast (`MOCK-PLATE-3` kolm saadetist `...0011`–`...0013`,
`MOCK-PLATE-1` saadetis `...0001`) samade reeglitega nagu päris värav:

- kõik antud kriteeriumid peavad sobima (AND); kriteerium ilma `operator: "EQ"`/`"NE"`-ta ei sobi millegagi;
- kuupäevaväljad on massiivid: esimene element `EQ`/`NE`/`LT`/`LE`/`GT`/`GE`, teine `LT`/`LE`/`GT`/`GE`;
- `transportMeansOrEquipmentId` sobib, kui id on veoki, kasutatava või veetava seadme number;
- järjekord on uuemad ees (`MOCK-PLATE-3` saadetised enne `MOCK-PLATE-1` oma).

Näiteks `mainTransportId` `MOCK-PLATE-1` või `usedEquipmentId` `CONT-MOCK-01` → üks rida;
`mainTransportId` `MOCK-PLATE-3`, `usedEquipmentId` `MOCK-TRAILER-3` või `carriedEquipmentId`
`MOCK-CONTAINER-3` → kolm rida. Leiu korral on päis `x-poll-more: false`. Kui midagi ei leita, on
vastus `[]` ja `x-poll-more: true`, sest päris värav alustab siis naaberväravate päringut. Pollimispäring
`{ "poll": true }` → `[]` ja `x-poll-more: false`.

```json
[
  {
    "uil": { "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440001" },
    "mainTransportId": "MOCK-PLATE-1",
    "mainTransportType": "1522",
    "transportRegCountry": "EE",
    "transportMode": "3",
    "dangerousGoods": null,
    "acceptanceDate": null, "acceptanceCountry": null,
    "deliveryDate": null, "deliveryCountry": null,
    "loadingDate": "2026-04-23T06:00:00Z", "loadingCountry": "EE",
    "unloadingDate": "2026-04-25T14:00:00Z", "unloadingCountry": "FI",
    "usedEquipmentIds": ["CONT-MOCK-01"],
    "usedEquipmentCategories": ["CN"],
    "usedEquipmentCountries": ["EE"],
    "carriedEquipmentIds": [],
    "carriedEquipmentCategories": [],
    "status": "ACTIVE",
    "createdAt": "2026-04-23T10:15:00Z"
  }
]
```

---

### 5. `dataset` — andmehulga päring

#### Use case: ühel autol kolm saadetist

Autonumber **MOCK-PLATE-3** (registreerimisriik **EE**) veab poolhaagist **MOCK-TRAILER-3** ja sellel
konteinerit **MOCK-CONTAINER-3**. Need annavad kolm saadetist ning kõik kolm numbrit leiavad sama kolmiku.
Kõigi sihtkoht on **Narva Mock Distribution Centre, Kadastiku 25, 20203 Narva, EE**. Vedaja on
**Mock Multi-pickup Carrier, Mustamäe tee 5, 10616 Tallinn, EE** ja juht **Mati Mock**.

| Dataset-ID | Pealelaadimine | Kaup | Brutokaal / maht | Veodokument | Ohtlik veos |
|---|---|---|---|---|---|
| `...0011` | Tallinn, Peterburi tee 10, 14.09.2026 06:00 UTC | Mööbel: kontorilauad ja -toolid | 2000 kg / 8,5 m³ | CMR `MOCK-CMR-0011` | ei |
| `...0012` | Tartu, Ringtee 20, 14.09.2026 09:00 UTC | Masinaosad: laagrid ja võllid | 3000 kg / 6,0 m³ | CMR `MOCK-CMR-0012` | ei |
| `...0013` | Pärnu, Tallinna mnt 30, 14.09.2026 12:00 UTC | Bensiin (UN 1203) terasvaatides | 1500 kg / 2,1 m³ | CMR `MOCK-CMR-0013` | UN 1203, klass 3, pakendirühm II, tunnel D/E |

(Dataset-ID-de täiskuju on `550e8400-e29b-41d4-a716-4466554400NN`.) Igal saadetisel on ka märkus
(`information`). Need on sünteetilised katseandmed, mitte tegeliku ADR-veo dokumentatsioon.

1. Leia saadetised veoki, haagise või konteineri numbri järgi:

```http
POST /developer/v1/transport-means
Content-Type: application/json
X-Road-Client: EE/GOV/70000097/test
X-Road-Id: 550e8400-e29b-41d4-a716-446655440099

{"identifier":"MOCK-TRAILER-3","scope":"local"}
```

Vastus on `found: 3` ja kolm `consignments` kirjet, igaühel oma täielik UIL. `scope: "existence"`
annab `registered: true`, `countryCode: "FI"` aga tühja tulemuse. Sama kolmiku annab ka
`POST /developer/v1/search` kehaga `{"mainTransportId":{"id":"MOCK-PLATE-3","operator":"EQ"}}`.

2. Küsi iga saadetise detailid `POST /developer/v1/dataset` kaudu (iga päringu jaoks oma `X-Road-Id`):

```json
{
  "uil": {"gateId":"EU-EE","platformId":"mock","datasetId":"550e8400-e29b-41d4-a716-446655440013"},
  "subsets": ["EU01","EU02","EU03","EU05"]
}
```

#### Vormi väljad: kust need dataset'i vastusest leiab

Kõige lihtsam on lugeda `consignment` objekti, mitte XML-i parsida. Tabeli JSON-teed on suhtelised
`consignment`-i suhtes; XML-tee on suhteline `SpecifiedSupplyChainConsignment` elemendi suhtes. Näide
on saadetisest `...0013`.

| Vormi väli | `consignment` JSON-tee | FTI010 XML-element | Näide |
|---|---|---|---|
| Lähtekoht | `mainCarriageLogisticsTransportMovement.loadingTransportEvent.occurrenceLogisticsLocation.postalTradeAddress.countryId` (+ `cityName`) | `MainCarriageLogisticsTransportMovement/LoadingTransportEvent/OccurrenceLogisticsLocation/PostalTradeAddress/CountryID` | `EE` (Pärnu) |
| Sihtkoht | `…unloadingTransportEvent.occurrenceLogisticsLocation.postalTradeAddress.countryId` (+ `cityName`) | `…/UnloadingTransportEvent/OccurrenceLogisticsLocation/PostalTradeAddress/CountryID` | `EE` (Narva) |
| Laadimine | `…loadingTransportEvent.actualOccurrenceDateTime.dateTimeString` (+ `dateTimeString@format`) | `…/LoadingTransportEvent/ActualOccurrenceDateTime/udt:DateTimeString` | `202609141200+0000`, formaat `205` → 14.09.2026 15:00 Eesti aja järgi |
| Tarneaeg | `…unloadingTransportEvent.actualOccurrenceDateTime.dateTimeString` | `…/UnloadingTransportEvent/ActualOccurrenceDateTime/udt:DateTimeString` | `20260915`, formaat `102` → **ainult kuupäev** 15.09.2026 |
| Transpordirežiim | `mainCarriageLogisticsTransportMovement.modeCode` | `…/ModeCode` | `3` = maanteetransport (CL-037: 1 meri, 2 raudtee, 3 maantee, 4 õhk, 8 siseveed) |
| Veok (reg nr) | `…usedLogisticsTransportMeans.Id` (+ `registrationTradeCountry.Id`) | `…/UsedLogisticsTransportMeans/ID` | `MOCK-PLATE-3` (EE) |
| Haagis | `utilizedLogisticsTransportEquipment.Id`, kui `categoryCode` on `TE` | `UtilizedLogisticsTransportEquipment/ID` | `MOCK-TRAILER-3` |
| Konteiner | `utilizedLogisticsTransportEquipment.carriedAssociatedTransportEquipment.Id`, kui `categoryCode` on `CN` | `UtilizedLogisticsTransportEquipment/CarriedAssociatedTransportEquipment/ID` | `MOCK-CONTAINER-3` |
| Veodokument nr | `transportContractReferencedDocument.Id` (`typeCode` `730` = CMR) | `TransportContractReferencedDocument/ID` | `MOCK-CMR-0013` |
| Saadetise ID (UIL) | vastuse `uil` | — | `EU-EE/mock/550e8400-…-446655440013` |
| Saatja nimi / riik / aadress | `consignorTradeParty.name`, `.postalTradeAddress.countryId`, `.streetName`, `.postcodeCode`, `.cityName` | `ConsignorTradeParty/…` | Pärnu Mock Supplier, EE, Tallinna mnt 30, 80042 Pärnu |
| Saaja | `consigneeTradeParty.…` (sama kuju) | `ConsigneeTradeParty/…` | Narva Mock Distribution Centre |
| Vedaja | `carrierTradeParty.…` (sama kuju) | `CarrierTradeParty/…` | Mock Multi-pickup Carrier, EE, Mustamäe tee 5 |
| Kauba kirjeldus | `natureIdentificationTransportCargo.Identification` | `NatureIdentificationTransportCargo/Identification` | Bensiin (UN 1203) terasvaatides |
| Brutokaal | `grossWeightMeasure` + `grossWeightMeasure@unitCode` | `GrossWeightMeasure/@unitCode` | `1500` `KGM` → 1500 kg |
| Maht | `grossVolumeMeasure` + `grossVolumeMeasure@unitCode` | `GrossVolumeMeasure/@unitCode` | `2.1` `MTQ` → 2,1 m³ |
| Pakkeid | `packageQuantity`, pakendi liik `transportLogisticsPackage.type` | `PackageQuantity`, `TransportLogisticsPackage/Type` | 10 × Terasvaat |
| Ohtlik kaup (lipp) | `includedSupplyChainConsignmentItem.applicableTransportDangerousGoods` on olemas | `IncludedSupplyChainConsignmentItem/ApplicableTransportDangerousGoods` | ainult `...0013` |
| UN number | `…applicableTransportDangerousGoods.uNDGIdentificationCode` | `…/UNDGIdentificationCode` | `1203` → „UN1203” |
| Ohuklass | `…applicableTransportDangerousGoods.hazardClassificationId` | `…/HazardClassificationID` | `3`; tekst „tuleohtlik vedelik” tuleb ADR klassitabelist, mitte andmetest |
| Pakend | `…packagingDangerLevelCode` (pakendirühm) + `…specifiedLogisticsPackage.type` / `.typeCode` | `…/PackagingDangerLevelCode`, `…/SpecifiedLogisticsPackage/TypeCode` | `II`; `Terasvaat` (`1A1`) |
| Ohtliku kauba nimetus / tunnel | `…properShippingName`, `…tunnelRestrictionCode` | `…/ProperShippingName`, `…/TunnelRestrictionCode` | PETROL, `D/E` |
| Juht | `mainCarriageLogisticsTransportMovement.masterResponsibleTransportPerson.givenName` / `.familyName` | `…/MasterResponsibleTransportPerson/…` | Mati Mock |
| Märkused | `information` | `Information` | ADR-veos: juhil peab olema kehtiv ADR-koolitustunnistus. |

`consignment` JSON-i teeb xml-mapper ja sellel on mõned omapärad, millega vormi kood peab arvestama:

- **Võtmenimed:** esimene täht on väike ning iga `id` (tõstutundetult) muutub `Id`-ks. Seepärast on
  võtmed näiteks `Id`, `countryId`, `uNDGIdentificationCode`, `Identification` (suure algustähega) ja
  `wIdthMeasure`.
- **Atribuudid** on eraldi võtmed kujul `nimi@atribuut`, nt `grossWeightMeasure@unitCode: "KGM"`.
  Ühikukood tuleb vormil teisendada, nt `KGM` → kg ja `MTQ` → m³.
- **Kõik väärtused on stringid.** Element, mis XML-is kordub (nt kaks
  `IncludedSupplyChainConsignmentItem`-i), muutub **massiiviks**; ühekordne element jääb objektiks.
  Loe neid alati mõlemat kuju arvestades.
- **Kuupäevad:** formaat `205` on `CCYYMMDDHHMM±HHMM` (näidistes UTC, `+0000`) ja `102` on `CCYYMMDD`.
  FTI010 lubab mahalaadimise ja tarne jaoks ainult formaati `102`, seega kellaaega seal ei ole.
- `subsets`-i järgi mock XML-i ei filtreeri (live `mock`-platvorm samuti mitte).

Lookup'i `dangerousGoods` on ainult kolmandal `"1"` (gate'i HIGH indikaator), esimesel kahel `null`.
See indikaator ei ole ADR klassi number. Õigusteta memberCode `70000000` annab local-/detail-päringul 403.
Poll-päring on endiselt tühi ning `allgates` annab 501.

#### Rikkaliku üksik-dataset'i päring

Maksimaalse väljakattuvusega näidise saamiseks kasuta järgmist keha (samad päised nagu allpool):

```json
{
  "uil": { "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440002" },
  "subsets": ["EU01", "EU02", "EU03", "EU05"]
}
```

Rikkalik vastus sisaldab olemasoleva FTI010 XSD consignment-välju: saatjat, saajat, vedajat,
kontakte ja aadresse, kaubaartiklit, ohtlikku kaupa, varustust, veosündmusi/asukohti,
kaale/mahte, makseinfot ning viitedokumente ja manuseid. Näidis on sünteetiline, mitte
äriliselt kooskõlalise pärisveose kirjeldus. Iga korduvat välja esineb üks kord ning
choice-grupist kasutatakse esimest varianti; piiramatu korduste arvu tõttu pole lõplikku
„kõige suuremat” dataset'i. XML valideeritakse repo FTI010 XSD vastu täielikus test-envelope'is;
API tagastab endiselt vaid `SpecifiedSupplyChainConsignment` fragmendi JSON-i `xml` väljas.

Rikkalikus näidises on iga väli täidetud, kuid väärtused on kohatäited (`MOCK-ID`, `MOCK-Name` jne):
see sobib struktuuri kaardistamiseks, aga mitte sisu kontrollimiseks. Sisuliste väärtustega näited on
`MOCK-PLATE-3` saadetised eespool. Rikkalik näidis valitakse ainult täpse `EU-EE/mock/...0002` UIL-i
puhul. Kõik muud UIL-id saavad lihtsa `MOCK-PLATE-1` vastuse. Mock ei filtreeri XML-välju subsets'i järgi:
kõigi nelja küsimine dokumenteerib maksimaalse päringu, kuid üks lubatud alamhulk annab sama
rikkaliku XML-i. Õigusteta `70000000` annab ka selle ID korral 403. Uut dataset'i ei lisata
search/transport-means tulemustesse; seda küsitakse otse UIL-i järgi.

**Päring**
```
POST /developer/v1/dataset
X-Road-Client: EE/GOV/70000097/tram
X-Road-Id: 4894e35d-bf0f-44a6-867a-8e51f1daa7e0
Content-Type: application/json

{
  "uil": { "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440001" },
  "subsets": ["EU01", "EU05"]
}
```

**Vastus `200`**
```json
{
  "uil": { "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440001" },
  "subsets": ["EU01", "EU05"],
  "xml": "<SpecifiedSupplyChainConsignment ...>...</SpecifiedSupplyChainConsignment>",
  "consignment": {
    "grossWeightMeasure": "15000.00",
    "grossWeightMeasure@unitCode": "KGM",
    "packageQuantity": "120",
    "mainCarriageLogisticsTransportMovement": {
      "modeCode": "3",
      "usedLogisticsTransportMeans": { "Id": "MOCK-PLATE-1", "Id@schemeAgencyId": "5", "registrationTradeCountry": { "Id": "EE" } }
    }
  }
}
```

`xml` on FTI010 `SpecifiedSupplyChainConsignment` fragment ja `consignment` on sama sisu JSON-ina.

**Vead**

| Staatus | `code` | Millal |
|---|---|---|
| 400 | `MISSING_SUBSET` | `subsets` tühi või puudub |
| 403 | `FORBIDDEN_SUBSET` | `memberCode 70000000`; keha: `authorityId`, `deniedSubsets` (= küsitud), `permittedSubsets: []` |

---

### 6. `follow-up` — follow-up sõnum

**Päring**
```
POST /developer/v1/follow-up
X-Road-Client: EE/GOV/70000097/tram
X-Road-Id: 4894e35d-bf0f-44a6-867a-8e51f1daa7e0
Content-Type: application/json

{
  "uil": { "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440001" },
  "message": "Palun täpsustage veose kaal.",
  "referenceIds": ["REF-1"],
  "files": []
}
```

**Vastus `200`**
```json
{ "uil": { "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440001" }, "status": "DELIVERED", "message": "Palun täpsustage veose kaal." }
```

---

## Erinevused päris liidesest

- Autentimist ei toimu — `X-Road-Client` on vaba (v.a `00000000`); ühtki registrikirjet ei kontrollita.
- Andmed on staatilised ja ainult mockis. Leping on päris väravaga sama (vt „Mock ja live on 1:1”),
  aga päris värav neid näidissaadetisi ei leia.
- `dataset` alamhulgakontroll ei võrdle küsitud hulki lubatuga — `403` tuleb ainult `memberCode 70000000` puhul.
- Fan-out'i / pollimist tegelikult ei toimu. `search` tühi tulemus annab `x-poll-more: true` nagu
  päris värav, kuid pollimine tagastab kohe `[]` ja `x-poll-more: false`.
- Vastuseajad on kohesed; `502 GATEWAY_UNAVAILABLE` teed ei ole (päris väravas tuleb see `core` tõrke korral).

## Postmani kollektsioon

[`eFTI-developer-mock.postman_collection.json`](eFTI-developer-mock.postman_collection.json) —
kõik 20 päringut (6 operatsiooni õnnelikud teed + veajuhud + tervisekontroll), kaustadesse
jaotatud, testiskriptidega. `baseUrl` muutuja vaikeväärtus on `https://eftidev.kemitaws.ee`;
lokaalseks kasutuseks vaheta see `http://localhost:8088` vastu. `X-Road-Id` kasutab
Postmani `{{$guid}}`-i, seega iga päring saab värske UUID-i.

Impordi: Postman → File → Import → vali fail. Käivita üksik päring või terve kollektsioon
(Runner).

## Test

`tests/http/developer-mock.http` (jookseb `http-tests` compose-teenuse kaudu:
`TEST_FILES=tests/http/developer-mock.http docker compose run --rm http-tests`).

## Küsimused

Sten Viljus — <Sten.Viljus@Askend.com>.
