# ADR-006: X-Roadi identiteedimudel ja alamhulkade õigused

**Otsus (otsustajad täpsustamata, 01.09.2026):**

> **Muudatus (02.09.2026):** X-Roadi pind ei ole enam eraldi konteiner. `DSL/Ruuter-xroad/xroad/`
> liikus põhi-Ruuteri projektiks `DSL/Ruuter/xroad/` ja seda teenindatakse `/xroad/` all pordil
> 8086. Eraldi `ruuter-xroad` konteiner, port 8087, `constants-xroad.ini` ja `ruuter-xroad.yaml`
> on kustutatud. Võrguisolatsiooni nõue **ei muutu sisuliselt**, aga on nüüd selgesõnaline
> **ingressi piirang**: avalik pöördproksi / ingress ei tohi `/xroad/**` teed marsruutida, ja
> ainult turvaserver tohib selleni pääseda. Kaks meetodipõhist guardi asendati ühe
> projektitasemelise `xroad/.guard.yml`-iga (`xroad/GET/health/.guard.yml` `override_ancestors`
> hoiab health-probe avalikuna).

## Otsus

### 1. X-Roadi liides on REST, mitte SOAP

Kasutame X-Road v7 **REST-sõnumiprotokolli**. Vead tagastatakse RFC 7807 kujul
(`docs/specs/errors.json`), mitte SOAP fault'ina. WSDL-i (`efti-xroad.wsdl`) ei ole ega tule.

Sellest järeldub, et **`protocolVersion` kontrolli ei ole**: REST-protokollis on versioon
*tarbija* URL-i `/r1/` prefiks, mille tarbija enda turvaserver ära tarbib ja mida teenusepakkujani
ei edastata. Meie enda lepingu versioon on marsruudi `/v1`.

### 2. Helistaja identiteet on ORGANISATSIOON, mitte isik

X-Roadi turvaserver tuvastab helistaja mTLS-iga ja edastab tulemuse päises `X-Road-Client` kujul
`instance/memberClass/memberCode[/subsystemCode]`. `memberCode` on Äriregistri kood, mis seotakse
`authorities.registry_code` väärtusega. Väravale piisab päise usaldamisest — turvaserver on
tuvastamise juba teinud.

### 2a. Väravas on ainult teenusepakkuja pool (provider), klienti ei ole

Topoloogia:

```
TRAM / LOIS2 / ANTS (NES kaudu)
      ↓  tarbija päring /r1/EE/GOV/70003158/efti-gate/{teenus}/v1
  tarbija turvaserver
      ↓  X-Roadi võrk — turvaserverite vaheline mTLS + allkirjastamine
  MEIE turvaserver
      ↓  lihttekst HTTP meie enda võrgus, lisab X-Road-* päised
  ruuter :8086  /xroad/**   ← see on see, mis on ehitatud (xroad projekt)
```

`xroad` projekt on **teenusepakkuja adapter**, mida kutsub *meie oma* turvaserver. Tarbija (kliendi)
poolt ei ole ehitatud — värav ei kutsu ise ühtegi X-Roadi teenust.

> ### ⚠ Turvanõue: `/xroad/**` peab olema ligipääsetav AINULT oma turvaserverist
>
> `X-Road-Client` on **tavaline HTTP päis**, mille väravab usaldab tingimusteta, sest tuvastamine on
> juba tehtud turvaserveris. Sellest järeldub, et **kogu autentimismudel toetub võrgu
> isolatsioonile**: kes iganes suudab `/xroad/**` teeni otse pääseda, saab saata
> `X-Road-Client: EE/GOV/<suvaline-registrikood>/x` ja esineda **mis tahes registreeritud asutusena**
> — see tähendab kohe kogu tema alamhulkade õiguste avalikustamist ja hiljem, kui `core`-i edastus on
> ühendatud, ka täielikku andmeligipääsu. Kuna `/xroad/**` jagab nüüd porti 8086 avaliku API-ga, on
> see **ingressi** probleem, mitte pordi avaldamise oma.
>
> Juurutamisel on seega **kohustuslik**:
> - avalik ingress / pöördproksi **ei tohi** `/xroad/**` teed marsruutida — sellele teele vasta 404;
> - võrgureegel (NetworkPolicy / security group) peab lubama `/xroad/**` liiklust **ainult** oma
>   turvaserveri aadressilt (nt eraldi sisemine kuulaja või proksi, milleni ainult turvaserver ulatub).
>
> `compose.override.yml` avaldab 8086 `localhost`-i, aga see fail on **ainult lokaalseks
> arenduseks**.
>
> Alternatiiv, mis sellest sõltuvusest vabaneks, oleks mTLS ka turvaserveri ja adapteri vahel, aga
> X-Roadi juurutusmudel seda ei eelda ja RIA seda ei nõua.

### 2b. Adapter kutsub `core`-i sisemise teenusetokeniga

Adapter peab jõudma `core`-i autoriteedi-marsruutideni, aga
`efti/POST/api/v1/authority/.guard.yml` nõuab TIM-i JWT-d, mida adapteril ei ole kusagilt võtta, ja
projektiülene `template:` ei tööta (Ruuteri projekti *sees* töötab — `dataset-local.yml:18` ja
`authority/search.yml:47` kasutavad seda — aga `efti` / `xroad` piiri üleselt mitte).

Lahendus: `core`-i autoriteedi-guard saab **teise kredentsiaali** — kui päis
`X-Internal-Service-Token` klapib konstandiga, luba ja jäta TIM-i kutse vahele. Läbikukkumisharu on
endine JWT-tee, mitte lubamine.

See on **üldine sisemise teenuse kredentsiaal**, mitte X-Roadi oma: `core` ei tea X-Roadist midagi
ja mooduli piiri nõue jääb kehtima. Sama tokenit vajab ka G2G sissetulev liiklus (`AGENTS.md` märgib
seda juba).

**Miks see piisab:** `core`-i autoriteedi-käsitlejad **ei kasuta helistaja identiteeti üldse** —
`authority/dataset.yml` loeb ainult `body.uil` ja `body.subsets`, `authority/follow-up.yml:12`
kirjutab `requestingUserId: ""`, `authority/consignments-search.yml` edastab keha otse ReSQL-i,
`authority/search.yml` ei puuduta helistajat. Identiteet loeb ainult guardis, ja seal ainult
küsimusena "kas see on ADMIN või AUTHORITY". Seega ei pea adapter kasutajat teesklema — autoriseerimise
teeb ta ise ära (organisatsioon `X-Road-Client`-ist, alamhulgad `authorities.subsets`-ist) enne
edastamist.

**Miks mitte TIM-i teenusekonto:** modelleeriks masina inimesena, nõuaks kredentsiaalide rotatsiooni
ja lisaks igale päringule TIM-i pöördumise; lahendatud identiteet oleks kasutaja, mitte X-Roadi
organisatsioon. **Miks mitte mTLS adapteri ja core-i vahel:** Ruuteri `http.post`-il puudub
igasugune märk kliendisertifikaadi toest, ja hüpe on juba usaldustsooni sees.

**Päiste vastendus.** `X-Road-Id` → `x-request-id`. See ei ole kosmeetika: kõik kolm `core`-i
autoriteedi-käsitlejat loevad `x-request-id`-d ja `authority/search.yml` kasutab seda multiplekseri
küsitlusvõtmena.

> ### `X-Road-Id` peab olema UUID — see on tarbijalepe
>
> `core` annab `x-request-id` edasi **tüübitud UUID-parameetritele**: multiplekseri
> `@POST("/first/:searchId") fun multiplex(..., @PathParam searchId: UUID)`
> (`MultiplexerRoutes.kt:21`, sama real 43) ja edelivery `RequestKey(partyId, e.requestId.uuid)`
> (`InternalRoutes.kt:20`). Mõlemad viskavad erindi kõige muu peale.
>
> **X-Roadi REST-protokoll seda ei garanteeri:** turvaserver genereerib UUID-i ainult siis, kui
> tarbija selle ära jätab — tarbija infosüsteem võib määrata suvalise unikaalse sõne, mille
> turvaserver muutmata edasi annab. Kontrollimata jätmine tähendab, et täiesti korrektne sõnumi id
> lõhub kogu väravatevahelise tee, ja halvimal juhul **vaikselt**: `core`-i `search.yml` ei kontrolli
> multiplekseri staatust ja `respond_first` ei sea `status`-t, seega vastaks värav 200-ga vale kehaga.
>
> Seetõttu kontrollib guard kuju ja tagastab **400 `INVALID_REQUEST_ID`**. Kontroll on **ainult
> kujuline** (5 osa pikkustega 8-4-4-4-12): kuueteistkümnendsüsteemi märkide valideerimine nõuaks
> regulaaravaldist ja ükski DSL fail repos ei kasuta `.match` / `.test` / `RegExp`, seega on see tugi
> tõestamata. Kuju kontroll püüab reaalse juhtumi (`ITSYS-2026-000123` annab 3 osa), kuigi sama
> kujuga mitte-hex sõne jõuaks `core`-ini.
>
> Nõue kehtib **kogu X-Roadi pinnal**, ka `subsets`-il ja `echo`-l, mis `core`-i ei edasta. Üks
> ühtne lepe on tarbijale lihtsam kui marsruudipõhine maatriks, ja nii ei saa helistaja avastada oma
> alamhulki id-ga, mis järgmise päringu peal läbi kukuks.

> ### Teadaolev piirang: küsitlusvõti on jagatud
>
> `core`-i `poll_remaining` (`authority/search.yml:15-19`) teeb
> `GET multiplexer/api/v1/rest/${requestId}` **ilma omaniku kontrollita**, ja
> `MultiplexerRoutes.kt:43` tühjendab järjekorra sellele id-le, mis talle antakse. Kuna `X-Road-Id`
> on täielikult helistaja kontrolli all, saab üks asutus teise pooleliolevat otsingut tühjendada, kui
> ta id-d ära arvab või näeb (90 s vahemälu TTL piirab akent).
>
> Sama auk on JWT-teel, seega on see `core`-is varasem — aga käesolev töö avab selle X-Roadi
> helistajatele ja **dokumenteerib id taaskasutuse kui ettenähtud mehhanismi**, seega kuulub see
> siia. Mõju on piiratud: lekib identifikaatori-tasemel otsingu metaandmed pädevate asutuste vahel,
> kellel igaühel on määruse 2024/1942 järgi piiramatu identifikaatoripäring.
>
> Sulgub siis, kui lahendatud asutuse id hakkab `core`-ini jõudma (vt auditi lugu). Lisaks tuleb
> tarbijapoolne eeldus välja öelda: turvaserveri kaudu uuesti saadetud päring saab tavaliselt **uue**
> `X-Road-Id`, seega küsitlemiseks peab tarbija infosüsteem id-d teadlikult samaks jätma.

> ### Teadaolev piirang: `core`-i täielik katkestus annab 500, mitte 502
>
> `check_core_status` rakendub alles siis, kui `core` on **vastanud**. Ühenduse tõrge, DNS-i viga või
> 70 s ajalõpu möödumine viskab erindi `http.post`-i sees, ja `ruuter.yaml` seab
> `stop_in_case_of_exception: true`, seega jooks katkeb ja Ruuter annab oma üldise 500 — mitte 502
> `GATEWAY_UNAVAILABLE`, mille jaoks see kood loodi. Lisaharuga seda parandada ei saa (jooks ei jõua
> sinna). Tuleb kinnitada, kas turvaserver kordab 5xx peale päringut.

**Vigade edastamine.** `core` tagastab ad-hoc kujusid (`{"error": "Platform Not Found"}`), mitte RFC
7807. Adapter mähib need. Staatus normaliseeritakse **502 `GATEWAY_UNAVAILABLE`**-ks, mitte ei
edastata `core`-i oma staatust: `errors.json` seob iga koodi täpselt ühe staatusega
(`GATEWAY_UNAVAILABLE` = 502) ja RFC 7807 nõuab, et keha `status` klapiks HTTP staatusega — seega
suvalise staatuse edastamine sunniks kas välja mõeldud koodi või koodi/staatuse vasturääkivuse.
`core`-i tegelik staatus ja keha kantakse edasi väljades `coreStatus` / `coreResponse`, seega ühtegi
diagnostikat ei kaota.

### 3. Autoriseerimise allikas on `authorities.subsets`

`users` tabelis **ei ole** `subsets` veergu ega ka seost asutusega (`roles TEXT[]` on ainus
õigusteveerg). Seega JWT/TARA teel ei ole alamhulkade õigusi kusagilt lugeda. Ainus olemasolev
alamhulkade register on `authorities.subsets TEXT[]`, mille võti on `registry_code` — täpselt see,
mille `X-Road-Client` annab.

### 4. `X-Road-UserId` ei anna kunagi õigusi

X-Road seda päist **ei autendi** — see on helistaja enda väidetud väärtus. Ligipääsu otsust see ei
mõjuta.

Päis on ette nähtud GDPR art 30 auditi jaoks, aga **auditikirjutajat veel ei ole**: mitte ükski DSL
ei kirjuta `audit_log` tabelisse, `insert_audit*.sql` faili ei eksisteeri (ainus viide on
admin-liidese *lugemine* `admin/GET/v1/audit.yml`), ja `ruuter.yaml` seab
`display_request_content: false`, seega päis ei jõua ka päringulogisse. Vt lahtisi küsimusi.

### 5. Õiguste päring tagastab loendi, mitte jah/ei vastuse

`GET /xroad/v1/subsets` tagastab helistaja enda lubatud alamhulgad. Registrikood võetakse **ainult**
turvaserveri seatud päisest, mitte päringu kehast — nii ei saa helistaja küsida teise organisatsiooni
õiguste kohta.

## Skeem

Uusi veerge ega migratsioone ei ole. Kasutame olemasolevat:

| Veerg | Tüüp | Sisu |
|---|---|---|
| `authorities.registry_code` | `TEXT` | Äriregistri kood; vastab `X-Road-Client` `memberCode`-le |
| `authorities.subsets` | `TEXT[]` | Alamhulgad, mida see asutus tohib pärida |
| `authorities.status` | `authority_status` | Ainult `ACTIVE` autendib |

## Päised

| Päis | Kohustuslik | Roll |
|---|---|---|
| `X-Road-Client` | jah | **Kredentsiaal.** `instance/memberClass/memberCode[/subsystemCode]`. Nii 3- kui 4-osaline kuju on kehtiv X-Roadi kliendi id. |
| `X-Road-Id` | jah | Sõnumi id. **Peab olema UUID** — vt §2b; guard tagastab muidu 400 `INVALID_REQUEST_ID`. Vastendub `x-request-id`-ks. |
| `X-Road-Service` | ei | Turvaserveri seatud; informatiivne |
| `X-Road-UserId` | ei | Lõppkasutaja isikukood. **Ainult audit — õigusi ei anna.** |
| `X-Road-Represented-Party`, `X-Road-Issue` | ei | Informatiivsed |

## Kontroll (`DSL/Ruuter/xroad/.guard.yml`)

Guard **autendib ainult** — alamhulkade kontrolli ta ei tee (vt allpool):

1. `X-Road-Client` päis puudub või on vigane → **401** `unauthorized`
2. `X-Road-Id` päis puudub → **400** `missing-required-header`
3. `get_authority_by_registry_code` ei leia ühtegi `ACTIVE` asutust → **403** `forbidden`
4. Sama registrikoodiga on **rohkem kui üks** `ACTIVE` asutus → **403** `forbidden`
   (registri seadistusviga; suvalise rea valimine annaks ühele organisatsioonile teise õigused)
5. Muidu lubatud

Marsruudi tasemel lisanduvad (`POST /xroad/v1/dataset`; `POST /xroad/v1/vehicle` nõuab EU02-t):

6. Alamhulkade loend on tühi või puudub → **400** `MISSING_SUBSET`
7. Küsitud alamhulk ei ole `authorities.subsets` sees → **403** `FORBIDDEN_SUBSET`
8. `core` vastab staatusega ≥ 400 → **502** `GATEWAY_UNAVAILABLE`, `core`-i staatus ja keha väljades
   `coreStatus` / `coreResponse`

`X-Road-Client` on kredentsiaal, seega selle puudumine on 401 mitte 403 — sama hoiak nagu
platvormide `X-Api-Key` guardil (ADR-004).

**Keeldumine on läbikukkumisharu** ja iga lubav tee on selgesõnaline positiivne tingimus. See
järjekord on kriitiline: kui ReSql tagastab mitte-massiiv keha (500 veaobjekt, parameetri
valideerimise viga), on `body.length` `undefined` ja kõik võrdlused sellega on `false` — päring peab
sattuma keeldumisele, mitte lubamisele. Ühe negatiivse tingimusega kirjutatud kontroll, mille
läbikukkumisharu on `guard_success`, kukub täpselt selle sisendi peal **lahti** (fail-open).

Guard on **üks projektitasemel fail** `xroad/.guard.yml` (Ruuter ≥ 0.9.7-rc, #39), mis katab iga
meetodi `/xroad/**` all. Varem oli see kaks käsitsi sünkroonis hoitud faili (`GET/v1/` ja `POST/v1/`),
sest guardid olid ainult kataloogipõhised ja puu on meetodipõhine — projektitasemel guard kaotab selle
duplikaadi ära.

Tervisekontroll on ainus erand: `xroad/GET/health/.guard.yml` seab
`declaration.override_ancestors: true`, mis **asendab** projektiguardi selle alampuu jaoks, nii et
`GET /xroad/health/ready` ei pea `X-Road-Client` päist saatma ja konteineri healthcheck töötab.

### Alamhulkade kontroll (`FORBIDDEN_SUBSET`)

Kontrolli teeb **marsruut, mitte guard**: guard autendib organisatsiooni, aga alamhulga parameetrit
võtab vastu ainult `POST /xroad/v1/dataset`, seega teeb kontrolli see marsruut ise. Iga uus alamhulga
parameetrit võttev marsruut peab sama tegema — guard ei tee seda tema eest.

Kontroll on SQL-is, mitte DSL-is: Postgresi massiivi sisalduvuse operaator
(`:requested_subsets <@ a.subsets` failis `check_authority_subsets.sql`) teeb selle ühe avaldisega,
samas kui ükski Ruuteri DSL fail repos ei kasuta `.every` / `.includes` / noolefunktsioone, seega
mootori JS-massiivi tugi on tõestamata. ReSql oskab juba `{ type: array }` parameetreid ja `::text[]`
teisendusi (`insert_authority.sql`).

Kaks lõksu, mis on koodis kommenteeritud:

- **`'{}' <@ ükskõik mis` on TRUE.** Tühi alamhulkade loend läbiks sisalduvuse testi. Seega lükkab
  marsruut tühja või puuduva loendi tagasi **enne** SQL-i kutsumist — **400** `MISSING_SUBSET`
  (`openapi.yaml` nõuab `subsetId`-l `minItems: 1`).
- **Osaliselt lubatud päring keelatakse tervikuna.** `["EU01","EU06"]`, kus ainult EU01 on lubatud,
  annab 403 — mitte vaikset EU01-ni kärpimist. Kärpimine annaks helistajale vastuse, mida ta ei
  küsinud, ja peidaks õiguste vea.

Keeldumine kannab `deniedSubsets` / `permittedSubsets` / `authorityId` RFC 7807 laiendusväljadena,
mitte `detail`-teksti sisse põimituna: nii on iga väärtus terviklik interpolatsioon (repos tõestatud
muster) ja masinliiklus saab massiividel otse hargneda, ilma lauset parsimata.

## Päring

```
GET /xroad/v1/subsets
X-Road-Client: EE/GOV/70000097/tram
X-Road-Id: abc-123

200 OK
{ "registryCode": "70000097", "authorityId": "auth-mta", "subsets": ["EU01", "EU05"] }
```

Guardi tulemust ei saa Ruuteris käsitlejale edasi anda, seega käsitleja teeb asutuse päringu
uuesti (sama kuju nagu `echo.yml`). **Sisemist `check-xroad-client` abi-marsruuti teadlikult ei
tehtud**: `xroad/POST/internal/` alla jääv marsruut oleks väljaspool kõiki guarde ja
väljastpoolt kättesaadav, mis laseks kellel tahes registrikoode läbi käia ja asutuste nimesid ning
alamhulki koguda. Üks lisapäring ReSQL-i on odavam kui see avatus.

## Põhjus

- **Loend, mitte jah/ei.** Loend on jah/ei ülemhulk — klient vastab enda küsimusele lokaalselt.
  Andmestiku päring võib nimetada kuni seitset alamhulka, mis jah/ei liidesega tähendaks seitset
  päringut. Loend on ka vahemällu salvestatav. Lisaks: jah/ei liidese sisendiks pakutud
  registrikood oleks üleliigne ja ohtlik — seda tuleks igal juhul päisega võrrelda.
- **Organisatsioon, mitte TARA ID-tõend.** Eepikas nimetatud masinliiklus (ANTS läbi NES-i, üle
  10 000 päringu tunnis piirioperatsioonide ajal) toimub ilma inimeseta, seega TARA tõendit ei ole
  kusagilt võtta. Organisatsioonipõhine mudel töötab mõlemal juhul.
- **`authorities.subsets` on ainus olemasolev register.** `users.subsets` veergu ei ole ei
  migratsioonides ega `docs/specs/db/schema.sql`-is, ning `users` real puudub seos asutusega.

## Mida see mujal muudab

Need dokumendid väitsid vastupidist ja on selle otsusega korrigeeritud:

- `docs/architecture/integrations/README.md` §1.6 — väitis, et X-Roadi sõnum kannab TARA
  ID-tõendit, mida valideeritakse nagu otselogimist. See on teemaülene reegel, mis blokeeris
  valitud lahenduse.
- `docs/architecture/integrations/x_road_integration.md` — kirjeldas SOAP-i ja Java mooduleid
  `ee-adapter`/`core`, mida ei ole olemas; tegelik teostus on Ruuteri projekt `DSL/Ruuter/xroad/`.
- `docs/specs/permissions-matrix.md` §3.2 ja §7 — `FORBIDDEN_SUBSET` allikas.
- `docs/specs/openapi.yaml` — `User.subsets` kirjeldab veergu, mida ei ole.

`docs/specs/data-transformations.md` §428 ütles juba varem `requested subsets ⊆
authorities.subsets` — see oli õige ja jääb muutmata.

## Sõidukipäring (`POST /xroad/v1/vehicle`)

Esimene päris äriteenus sellel pinnal: **sisse tuleb auto number, vastu liigub see, mida värav tema
kohta teab.**

**Vastuses ei ole andmestiku sisu.** Tagastatakse identifikaatori-tasemel metaandmed —
`uil` (gateId/platformId/datasetId), veovahendi väljad, kuupäevad, riigid, veoseadmed. Sisu saab
helistaja seejärel `POST /xroad/v1/dataset`-iga, kasutades tagastatud `uil`-i, ja **see tee läbib
alamhulkade kontrolli**.

Selleks on eraldi ReSql fail `get_consignments_by_vehicle.sql`, mitte olemasolev
`get_consignments.sql`. Põhjused:

1. **Stabiilne väljaleping** väline X-Roadi tarbijale. `get_consignments` tagastab lisaks
   identifikaatori metaandmetele ka toore `xml` välja, mille skeem kuulub platvormile
   (`FTI004UploadIdentifierRequest`), mitte väravale — ehk see võib meie alt muutuda.
2. **`consignments.xml` on siin üleliigne**: see on *identifikaatori* XML nii, nagu platvorm selle
   saatis (`006-consignments.sql:54`), ja kannab samu välju, mis on juba veergudesse
   denormaliseeritud. Selle saatmine kahekordistaks vastuse ilma uue infota, marsruudil, mis peab
   olema odav ja suure läbilaskega.

> **Parandus.** Selle otsuse varasem sõnastus väitis, et `get_consignments.sql` taaskasutamine
> annaks "terve andmestiku" ja läheks `authorities.subsets`-ist mööda. **See oli vale.** Andmestiku
> sisu ei jõua kunagi Postgresesse — `authority/dataset.yml` tõmbab selle platvormilt
> `?subsetId=...`-iga, ja **just seal** alamhulkade õigus rakendub. `consignments.xml` on
> identifikaatori XML, mitte andmestik. Sama vale eeldust kasutati ka `authority/search.yml`
> süüdistamiseks; ka see on tagasi võetud. Kuratud projektsioon on endiselt õige valik, aga
> ülaltoodud põhjustel, mitte turvapõhjusel.

**Õigus: nõutav on `EU02`.** Delegeeritud määruse 2024/2024 järgi on EU02 "means of transport
(vehicle plate, container number)" — ehk täpselt see andmeklass, mida see päring tagastab. Ilma
selleta **403 `FORBIDDEN_SUBSET`**. Kasutab sama `check_authority_subsets.sql` sisalduvustesti, mis
andmestiku marsruut; uut õiguste päringut ei ole.

**Ainult kohalik register.** Multiplekserit ei kutsuta — "meile teadaolev" tähendab meie oma
registrit, ja ANTS-i nõue keelab ristvärava levipäringu sõnaselgelt.

**Veerg on `main_transport_id`**, mitte `vehicle_plate` — viimane esineb vanemates dokumentides, aga
ei ole skeemis kunagi olnud. Päring on indeksitoega (`idx_consignments_main_transport_id`) ja
kasutab sama kaheastmelist kuju nagu `get_authority_by_registry_code.sql`: kandidaadid kitsendatakse
indeksi kaudu, iga loogilise `(dataset_id, platform_id)` **viimane rida** lahendatakse filtreerimata,
ja alles välimine `WHERE` nõuab, et viimane rida numbrit ikka kannaks. Vastupidine järjekord
tähendaks, et parandatud numbriga uuesti laetud saadetis vastaks igavesti vanale numbrile.

Kaks teadlikku valikut, mida arvustaja võib tahta üle vaadata:

- **`status = 'ACTIVE'`** on positiivne lubatute loend, rangem kui `get_consignments.sql`-i
  `status != 'DELETED'` (mis tagastab ka `INACTIVE`). Tagajärg: sama numbri tulemused võivad erineda
  admin-otsingust.
- **`LIMIT 50` on serveripoolne**, mitte helistaja määratav. Levinud number võib vastata paljudele
  saadetistele, ja helistaja määratav limiit on täpselt see, kuidas identifikaatoripäringust saab
  massväljavõtte tööriist.

**Number sobitatakse täht-täheliselt.** `main_transport_id` on `TEXT`, mitte `CITEXT`, seega `123abc`
ei leia `123ABC`-d; ka tühikuid ei eemaldata (`.trim()` ei ole selle mootoris tõestatud). See on
dokumenteeritud, mitte vaikselt normaliseeritud, sest muutmine tähendaks veeru tüübi muutust või
funktsionaalset indeksit.

## Avatud küsimused

- **Auditikirjutaja puudub.** `X-Road-UserId` (ja üldse iga autoriseerimise sündmus) tuleks
  `audit_log` tabelisse kirjutada GDPR art 30 nõude täitmiseks — `logging-spec.md` kirjeldab välju,
  aga kirjutavat koodi ei ole kusagil. Kuni seda ei ole, on käesoleva ADR-i auditiväide **kavatsus,
  mitte teostus**. Kehtib kogu värava kohta, mitte ainult X-Roadi kanali kohta.
- **Sisemise teenusetokeni tootmisjuurutust EI OLE OLEMAS.** `INTERNAL_SERVICE_TOKEN` on literaal
  failis `constants.ini`, mille `docker/ruuter/Dockerfile` `COPY`-b tõmmisesse. (Enne `xroad`
  projekti liitmist oli sama väärtus ka failis `constants-xroad.ini` ja pidi olema
  bait-täpselt sama — see duplikaadi oht on kadunud.) Ruuteri konstantidel **ei ole** repos
  kusagil `${ENV}` asendust ega entrypointi, mis faili ümber kirjutaks — seega ainus viis väärtust
  muuta on **muuta versioonihaldusesse pandud faili ja mõlemad tõmmised uuesti ehitada**, ja miski ei
  anna häiret, kui see ununeb. See ei ole "tootmine peab süstima" vaid "süstimismehhanismi pole".

  Mõju, kui ununeb: avalikus repos olev sõne annab ADMIN-või-AUTHORITY-ekvivalentse ligipääsu kõigile
  neljale marsruudile `efti/POST/api/v1/authority/` all — **sealhulgas `consignments-search`, mis
  annab `incoming.body` otse `get_consignments`-ile koos helistaja määratud `limit`/`offset`-iga**,
  ehk saadetiste registri massväljavõtte. Token annab selle ilma TARA-ta ja ilma auditireata.

  Vaja on: (a) entrypoint, mis genereerib konstandifaili keskkonnamuutujast või monteeritud
  saladusest, ja (b) käivitusaegne või health-probe kontroll, et väärtus ei ole kaasa pandud
  vaikeväärtus. Kuni selleni kehtib teele `/efti/api/v1/authority/**` sama isolatsiooninõue nagu
  teele `/xroad/**` — mõlemad on nüüd samal pordil 8086, seega on isolatsioon **tee-, mitte
  pordipõhine**.

- **Teoreetiline: asendamata kohatäide autendiks.** Kui konstant `constants.ini`-st puuduks **ja**
  Ruuter jätaks `[#INTERNAL_SERVICE_TOKEN]` literaalseks (asendamata) selle asemel, et asendada see
  tühjaga, siis autendiks helistaja, kes saadab täpselt selle sõne. Guardi `!= ''` klauslid katavad
  tühja-asenduse haru, mitte literaalse. Ruuteri tegelik käitumine puuduva konstandi puhul on
  **kontrollimata**. Kuna mõlemad Dockerfile'id `COPY`-vad faili, mis võtme sisaldab, ei saa eeldus
  praegu täituda — kirjas ainult sellepärast, et see on ainus sisend, mis tingimuse tõeseks muudab
  ilma õiget saladust teadmata.
- **`FORBIDDEN_MULTI_AUTHORITY` veakood puudub.** Sama registrikoodiga mitme `ACTIVE` asutuse
  juhtum tagastab praegu üldise `FORBIDDEN`-i eristava `detail`-iga. Platvormide poolel on selle
  jaoks oma kood (`FORBIDDEN_MULTI_PLATFORM`); asutuste jaoks tuleks samaväärne kood
  `errors.json`-i lisada. Parem lahendus oleks vältida olukorda üldse — dublikaadikontroll
  `admin/POST/v1/authorities.yml`-is ja `admin/PUT/v1/authorities.yml`-is.

- **Alamhulkade väärtusteruum ei ole kokku lepitud.** Kasutusel on neli erinevat komplekti:
  `openapi.yaml` + UI enum (`EU01..EU07`), XSD `UnqualifiedDataType_34.xsd`
  (`EU01..EU03, EU05a/b/c, EU06` — ilma EU04/EU07-ta), `code/xml-mapper/src/efti/subsets/Subset.kt`
  (suvaline `CC`-prefiksiga väärtus + `full`/`identifier`) ning UI valikuloend, mis pakub ka Eesti
  riigisiseseid koode (`EE01/EE02/EE04/EE05a/EE05c/EE05d`). `docs/specs/db/schema.sql:215` CHECK-piirang
  (`EU01..EU07`) **ei ole** migratsioonidesse viidud ja seda ei lisatud: piirangu lisamine kokku
  leppimata sõnavarale murraks admin-liidese dokumenteeritud EE-valikud. Väärtusteruum tuleb enne
  piirangu lisamist otsustada.
- **X-Roadi marsruutide edastamine `core`-i** on lahtine. `efti/POST/api/v1/authority/.guard.yml`
  nõuab TIM-i JWT-d, mida `xroad` projektil ei ole, ja projektiülene `template:` ei tööta ka siis,
  kui mõlemad projektid jooksevad samas mootoris. Vajab sisemist teenusetokenit (`AGENTS.md` märgib
  sama vajadust G2G sisendi jaoks).
- **Alamhulkade jõustamine JWT/TARA teel** (`efti/POST/api/v1/authority/dataset.yml`) on
  disainiliselt blokeeritud, kuni `users` real ei ole ei `subsets` veergu ega seost asutusega.

## Seos `refactor/split-m2m-ruuter` haruga

See otsus on teostatud `dev` haru paigutuses, kus X-Roadi pind on põhi-Ruuteri projekt
`DSL/Ruuter/xroad/`. Harus `origin/refactor/split-m2m-ruuter` (ADR-005) nimetatakse pind ümber
`m2m`-ks ja marsruudid liiguvad. Vastavus liitmise jaoks:

| `dev` | `refactor/split-m2m-ruuter` |
|---|---|
| `DSL/Ruuter/xroad/POST/v1/` | `DSL/Ruuter-m2m/m2m/POST/xroad/v1/` |
| `DSL/Ruuter/xroad/GET/v1/` | `DSL/Ruuter-m2m/m2m/GET/xroad/v1/` |
| põhi-Ruuteri `constants.ini` / `ruuter.yaml` | `constants-m2m.ini` / `ruuter-m2m.yaml` |
| `tests/http/xroad-*.http` | `tests/http/xroad-*.http` |
