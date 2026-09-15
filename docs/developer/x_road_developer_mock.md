# X-tee arendaja-mock (`dev.efti.ee/developer/`)

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

```
Arendaja klient ──HTTP──> https://dev.efti.ee/developer/v1/{operatsioon}
                          (nginx → xtee-mock-teenus, konserveeritud vastus)
```

## Baas-URL ja teed

| | |
|---|---|
| Avalik baas-URL (sihtdomeen) | `https://dev.efti.ee/developer/` |
| Avalik baas-URL (praegune juurutus) | `https://eu-ee.pikker.dev/developer/` |
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
| Tuntud tunnus | `MOCK-PLATE-1` — annab `transport-means` / `search` tabamuse; kõik muu → tühi tulemus |
| Tuntud `uil` | `{ "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440001" }` |
| Rikkalik dataset | `{ "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440002" }` — täieliku väljakattuvusega sünteetiline FTI010 näidis |

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
| `identifier` | jah | mittetühi string; `MOCK-PLATE-1` annab tabamuse |
| `countryCode` | ei | kajastatakse vastuses tagasi |
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

**Vastus `200`** — massiiv. `mainTransportId.id == "MOCK-PLATE-1"` → üks rida; muidu `[]`.
Pollimispäring `{ "poll": true }` → `[]`. Vastuse päis `x-poll-more: false`.

```json
[
  {
    "uil": { "gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440001" },
    "mainTransportId": "MOCK-PLATE-1",
    "mainTransportType": "1522",
    "transportMode": "3",
    "transportRegCountry": "EE",
    "acceptanceDate": "2026-04-22T09:00:00Z", "acceptanceCountry": "EE",
    "deliveryDate": "2026-04-25T14:00:00Z", "deliveryCountry": "FI",
    "status": "ACTIVE",
    "createdAt": "2026-04-23T10:15:00Z"
  }
]
```

---

### 5. `dataset` — andmehulga päring

#### Use case: ühel autol kolm saadetist

Eraldi autonumber **MOCK-PLATE-3**, registreerimisriik **EE**, annab kolm saadetist.
Kõigi sihtkoht on **Narva Mock Distribution Centre, Kadastiku 25, Narva, EE**.

| Dataset-ID | Pealelaadimiskoht | Pealelaadimine | Kaup | Ohtlik veos |
|---|---|---|---|---|
| `550e8400-e29b-41d4-a716-446655440011` | Tallinn, Peterburi tee 10 | 2026-09-14 06:00 | Furniture, 2000 kg brutokaal | Ei |
| `550e8400-e29b-41d4-a716-446655440012` | Tartu, Ringtee 20 | 2026-09-14 09:00 | Machine parts, 3000 kg brutokaal | Ei |
| `550e8400-e29b-41d4-a716-446655440013` | Pärnu (XML-is `Parnu`), Tallinna mnt 30 | 2026-09-14 12:00 | Bensiin, 1500 kg brutokaal | UN 1203, ADR klass 3, pakendigrupp II |

Mahalaadimise kuupäev on kõigil **2026-09-15**. FTI010 selle välja date-format on `102`
(kuupäev), mistõttu lookup'i UTC kesköö ei tähenda tegelikku kokkulepitud kellaaega.
Need on sünteetilised katsed, mitte tegeliku ADR-veo dokumentatsioon.

1. Kontrolli `POST /developer/v1/transport-means` kaudu existence'i:

```http
POST /developer/v1/transport-means
Content-Type: application/json
X-Road-Client: EE/GOV/70000097/test
X-Road-Id: 550e8400-e29b-41d4-a716-446655440099

{"identifier":"MOCK-PLATE-3","countryCode":"EE","scope":"existence"}
```

Oodatav `registered: true`. Sama päring `scope: "local"` annab `found: 3` ja
kolm `consignments` kirjet. Igal on oma täielik UIL (`gateId: EU-EE`, `platformId: mock`).
Ka `POST /developer/v1/search` keha
`{"mainTransportId":{"id":"MOCK-PLATE-3","operation":"EQ"}}` annab sama kolmiku.

2. Küsi kõik kolm detaili järjest `POST /developer/v1/dataset` kaudu:

```json
{
  "uil": {"gateId":"EU-EE","platformId":"mock","datasetId":"550e8400-e29b-41d4-a716-446655440011"},
  "subsets": ["EU01","EU02","EU03","EU05"]
}
```

Kasuta samu päiseid, iga päringu jaoks oma X-Road-Id UUID-d ning asenda dataset-ID
järgmises päringus `...0012`, seejärel `...0013`. JSON-i `xml` väljast kontrolli:
`UsedLogisticsTransportMeans/ID` on kõigil `MOCK-PLATE-3`, `ConsigneeTradeParty` ja
mahalaadimiskoht on samad, `ConsignorTradeParty` ja pealelaadimiskoht on erinevad.
Ainult kolmandal on `ApplicableTransportDangerousGoods` (UN 1203, klass 3).

Lookup'i `dangerousGoods` on ainult kolmandal `"1"` (gate'i HIGH indikaator), esimesel
kahel `null`; see indikaator ei ole ADR klassi number. XML-i `HazardClassificationID` on `3`.
`countryCode: "FI"` ei anna selle EE auto tabamust; õigusteta memberCode `70000000`
annab local/detail-päringul 403. Poll-päring on endiselt tühi ning allgates on 501.

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

Uus näidis valitakse ainult täpse `EU-EE/mock/...0002` UIL-i puhul. Senine `...0001` ja
muud UIL-id säilitavad lihtsa staatilise vastuse. Mock ei filtreeri XML-välju subsets'i järgi:
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
  "xml": "<rsm:SpecifiedSupplyChainConsignment ...>...</rsm:SpecifiedSupplyChainConsignment>"
}
```

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
- Andmed on staatilised: search/transport-means tunnevad `MOCK-PLATE-1`; dataset annab
  `EU-EE/mock/...0002` korral rikkaliku näidise ja teiste UIL-ide puhul senise lihtsa näidise.
- `dataset` alamhulgakontroll ei võrdle küsitud hulki lubatuga — `403` tuleb ainult `memberCode 70000000` puhul.
- Fan-out'i / pollimist tegelikult ei toimu; `x-poll-more` on alati `false`.
- Vastuseajad on kohesed; `502 GATEWAY_UNAVAILABLE` teed ei ole (päris väravas tuleb see `core` tõrke korral).

## Postmani kollektsioon

[`eFTI-developer-mock.postman_collection.json`](eFTI-developer-mock.postman_collection.json) —
kõik 20 päringut (6 operatsiooni õnnelikud teed + veajuhud + tervisekontroll), kaustadesse
jaotatud, testiskriptidega. `baseUrl` muutuja vaikeväärtus on `https://eu-ee.pikker.dev`;
vaheta `https://dev.efti.ee` või `http://localhost:8088` vastu. `X-Road-Id` kasutab
Postmani `{{$guid}}`-i, seega iga päring saab värske UUID-i.

Impordi: Postman → File → Import → vali fail. Käivita üksik päring või terve kollektsioon
(Runner).

## Test

`tests/http/developer-mock.http` (jookseb `http-tests` compose-teenuse kaudu:
`TEST_FILES=tests/http/developer-mock.http docker compose run --rm http-tests`).

## Küsimused

Sten Viljus — <Sten.Viljus@Askend.com>.
