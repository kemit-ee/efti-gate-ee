# ADR-007: `transport-means` `scope: allgates` vastuse kuju

**MUSTAND (otsustajad täpsustamata, 03.09.2026) — valik variantide A, B ja D vahel on lahtine.**

Seotud: [issue #125](https://github.com/kemit-ee/efti-gate-ee/issues/125), [ADR-006](006-xroad-identity-and-subsets.md),
[ADR-008](008-adopt-datamapper.md) (variant D eeldab seda), PR #121.

## Kontekst

`POST /xroad/v1/transport-means` saab astmelise `scope` parameetri (PR #121 kommentaar, punkt 3):

| `scope` | Ulatus | Vastus | Latents |
|---|---|---|---|
| `existence` | kohalik | `{ identifier, countryCode, registered }` | p95 < 1 s |
| `local` *(vaikimisi)* | kohalik | `{ identifier, countryCode, found, consignments: [...] }` | sünkroonne |
| `allgates` | kohalik + naaberväravad | *(käesolev ADR)* | asünkroonne, `x-poll-more` + polling |

`scope` disaini keskne lubadus: **`local` ja `allgates` vastuse kuju on identne**, nii et võrgu
kaasamine on kliendile ühe välja muutus, mitte teine integratsioon.

`scope: local` tagastab `DSL/Resql/efti/POST/get_consignments_by_vehicle.sql` **kureeritud
projektsiooni**: `uil` kui `jsonb_build_object`, ~20 nimetatud välja, **ei mingit `xml` blob'i**.

`scope: allgates` peab tooma tulemused ka naaberväravatelt. Need lähevad läbi `core`
`efti/POST/api/v1/authority/search`, mis kutsub multiplekserit ja saab vastuse **X-Roadi otsingu
vastuse XML-ina**, mille `xml-mapper` `POST /api/v1/search/response-to-json` teisendab JSON-iks.

**`core` `authority/search.yml` ei anna ise järjekindlat kuju:**
- kohalik tabamus (`respond_local`) → `get_consignments.sql` **toored read** (`row_id`, `xml`,
  `status::text`, snake_case)
- kaug-tabamus (`respond_first` / `respond_rest`) → `xml-mapper` `search/response-to-json` väljund

Seega `allgates` haru peab kuju ise ühtlustama, kui lubadus kehtima jääb.

## Variandid

### Variant A — uus `xml-mapper` teisendusendpoint

Uus `xml-mapper` endpoint (nt `POST /api/v1/transport-means/response-to-json`, Kotlin,
`code/xml-mapper/`), mis emiteerib **sama kureeritud projektsiooni kuju** X-Roadi otsingu vastuse
XML-ist. `transport-means.yml` `allgates` haru:

1. kohalikud read: olemasolev `get_consignments_by_vehicle.sql` projektsioon
2. `core` search'i kaudu kogutud kaug-XML → uus `xml-mapper` endpoint → sama projektsioon
3. liidab mõlemad `consignments` massiivi, `found` = pikkus, `x-poll-more` edasi

**Poolt:**
- `scope` lubadus kehtib — `local` ↔ `allgates` vastuse kuju identne, üks mentaalne mudel
- `existence`-semantika jääb koherentseks (kõik kolm `scope` väärtust räägivad sama keelt)
- väline tarbija (ANTS / NES) saab stabiilse väljalepingu sõltumata sellest, mitu väravat vastas

**Vastu:**
- Kotlin-töö `xml-mapper`-is; uus teisendus, mida tuleb hooldada eFTI otsingu-vastuse XSD vastu
- veel üks liikuv osa teisendusahelas
- `xml-mapper`-il pole praegu veovahenditasandi projektsiooni mõistet — see on uus vastutus

### Variant B — lõdvenda lubadust

`allgates` tagastab tulemused **`core` search'i JSON-kujus** (mitte kureeritud projektsioonis).
Vastuse kuju `local` ja `allgates` vahel **erineb**; dokumenteeri `openapi.yaml`-is eraldi
skeemina (`TransportMeansAllgatesResponse` vs `TransportMeansLocalResponse`).

`transport-means.yml` `allgates` haru = sisuliselt olemasolev `DSL/Ruuter/xroad/POST/v1/search.yml`
(forward `core`-ile, `x-poll-more` edasi, `core_error` → 502), lisaks EU02 kontroll ja
fikseeritud `transportMeansOrEquipmentId` kriteerium.

**Poolt:**
- minimaalne — ~40 rida DSL-i, olemasoleva `search.yml` mustri kordus, **null Kotlin-tööd**
- ei lisa `xml-mapper`-isse uut vastutust
- kiireim tee `allgates` funktsionaalsuseni

**Vastu:**
- `scope` disaini elegants kannatab: klient, kes `local`-ilt `allgates`-le läheb, peab käsitlema
  kahte vastuse kuju
- `found` / `consignments` väljaleping erineb; "üks endpoint, üks kuju" lubadus katkeb
- `xml` blob võib kaug-tulemustes kaasa tulla (search'i kuju sisaldab seda), mis läheb vastuollu
  `transport-means`-i "ainult identifikaatoritasand, ei dataset-sisu" põhimõttega — vajab
  eraldi filtreerimist või selget hoiatust

### Variant D — DataMapperi mall, ühtne kohalikule ja kaugele

Eeldab [ADR-008](008-adopt-datamapper.md) (DataMapperi kasutuselevõtt). Üks Handlebars-mall
`DSL/DMapper/hbs/`-is, mida jooksutatakse **nii kohalike ReSql ridade kui kaug-otsingu tulemuste
peal** → üks kureeritud kuju mõlemale, sõltumata allikast.

`transport-means.yml`:
1. kohalikud read: `get_consignments_by_vehicle.sql` (või lihtsam `get_consignments.sql`) →
   DataMapper `transport_means` mall
2. kaug-tulemused: `core` search → `xml-mapper` `search/response-to-json` → **sama** DataMapper mall
3. liidab mõlemad, `found` = pikkus, `x-poll-more` edasi

**Poolt:**
- `scope` lubadus kehtib täielikult — `existence` / `local` / `allgates` räägivad sama keelt
- projektsioon on deklaratiivne, andmebaasita testitav mall — mitte Kotlin ega `jsonb_build_object`
- ei lisa `xml-mapper`-i vastutust; kihijaotus selgineb (vt ADR-008)
- muster kordub iga tulevase `allgates`-tüüpi marsruudi puhul

**Vastu:**
- eeldab ADR-008 ratifitseerimist — uus teenus `compose.yml`-is, `DSL/DMapper/` struktuur,
  õppimiskõver. See on kogu vastuse-kujundamise strateegia otsus, mitte ainult ADR-007 oma.
- kui ADR-008 lükkub edasi, lükkub ka see variant

## Miks variant C (DSL-poolne ümberkujundamine) ei lenda üldse

Kolmas mõeldav lähenemine — jätta kaug-tulemused `core` search'i kujusse ja **kujundada need
`transport-means.yml`-is endas** ümber kureeritud projektsiooniks — ei ole reaalne variant:

1. **Ruuteri DSL mootor ei toeta tõestatult massiivi-teisendust.** `.map()`, `Array.isArray`,
   noolefunktsioonid — ükski neist ei ole selles repos üheski DSL failis kasutusel, ja **kogu
   PR #121 on ehitatud selle piirangu ümber** (dokumenteeritud igas selle failis: `dataset.yml`
   deklaratsiooni kommentaar, `vehicle.yml` `check_input` / `check_lookup` kommentaarid,
   `check_authority_subsets.sql` päis). Massiivi elementhaaval ümberkujundamine nõuab täpselt
   neid konstruktsioone.

2. **Isegi kui mootor toetaks:** kaug-tulemused tulevad multiplekserist XML-ina, mille `xml-mapper`
   üldine `search/response-to-json` JSON-iks teeb. DSL-is ümberkujundamiseks tuleks selle JSON-i
   täpne skeem teada ja ~20 välja rea kohta YAML-is käsitsi vastendada — hooldamatu ja habras
   `xml-mapper`-i väljundi muutuste suhtes.

3. **Seega:** ümberkujundamine peab toimuma kas SQL-is (katab ainult kohalikke ridu — ei lahenda
   `allgates`-i) või `xml-mapper`-is (**see ongi variant A**). "Tee seda DSL-is" ei ole
   koodibaasis lubatud lähenemine ja variant C taandub sellele.

## Soovitus

- **Variant D**, kui [ADR-008](008-adopt-datamapper.md) läheb läbi — see on ainus variant, mis
  `scope` lubadust täielikult hoiab, ilma Kotlin-tööta, ja skaleerub järgmistele marsruutidele.
  ADR-007 ja ADR-008 on selle valiku kaudu tihedalt seotud.
- **Variant A**, kui DataMapperit ei võeta kasutusele, aga kureeritud väljaleping on välisele
  tarbijale oluline (ANTS / NES eeldab stabiilset skeemi).
- **Variant B** selge `openapi.yaml` märkusega, kui `allgates` on esialgu haruldane kasutus ja
  kiire kohaletoimetamine kaalub üles kahe kuju halva.

Otsust ei ole tehtud. Otsustajad: Sten Viljus + arhitektid. **Seotud otsus: [ADR-008](008-adopt-datamapper.md).**

## Tagajärjed

**Variant A:**
- uus `xml-mapper` endpoint + testid; eFTI otsingu-vastuse XSD sõltuvus
- `transport-means.yml` kutsub kohalike ja kaug-tulemuste peal sama projektsiooniloogikat
- `openapi.yaml`: üks `transport-means` 200 skeem, `x-poll-more` päis lisatud

**Variant B:**
- `transport-means.yml` `allgates` haru peegeldab `xroad/POST/v1/search.yml`-i
- `openapi.yaml`: kaks 200 skeemi `scope` järgi; `developer/x_road_authority_integration_guide.md`
  `scope` tabelis eraldi märkus, et `allgates` kuju erineb
- klient peab `allgates` puhul teadma `core` search'i väljaleppe

**Variant D:**
- ADR-008 tagajärjed (uus teenus, `DSL/DMapper/` struktuur)
- `DSL/DMapper/hbs/transport_means.hbs` — üks projektsioonimall
- `transport-means.yml` kutsub malli nii kohaliku kui kaug-tulemuse peal
- `openapi.yaml`: üks `transport-means` 200 skeem kõigi `scope` väärtuste jaoks

## Rakendatav changeset

Vt [issue #125](https://github.com/kemit-ee/efti-gate-ee/issues/125). Käesolev ADR fikseeritakse
(MUSTAND → OTSUS) siis, kui A/B/D valik on tehtud, enne #125 teostust. Variant D valik eeldab
[ADR-008](008-adopt-datamapper.md) ratifitseerimist.
