# ADR-006: X-Roadi identiteedimudel ja alamhulkade õigused

**Otsus (otsustajad täpsustamata, 01.09.2026):**

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
  ruuter-xroad:8087   ← see on see, mis on ehitatud
```

`ruuter-xroad` on **teenusepakkuja adapter**, mida kutsub *meie oma* turvaserver. Tarbija (kliendi)
poolt ei ole ehitatud — värav ei kutsu ise ühtegi X-Roadi teenust.

> ### ⚠ Turvanõue: adapter peab olema ligipääsetav AINULT oma turvaserverist
>
> `X-Road-Client` on **tavaline HTTP päis**, mille väravab usaldab tingimusteta, sest tuvastamine on
> juba tehtud turvaserveris. Sellest järeldub, et **kogu autentimismudel toetub võrgu
> isolatsioonile**: kes iganes suudab `ruuter-xroad`-i pordini 8087 otse pääseda, saab saata
> `X-Road-Client: EE/GOV/<suvaline-registrikood>/x` ja esineda **mis tahes registreeritud asutusena**
> — see tähendab kohe kogu tema alamhulkade õiguste avalikustamist ja hiljem, kui `core`-i edastus on
> ühendatud, ka täielikku andmeligipääsu.
>
> Juurutamisel on seega **kohustuslik**:
> - port 8087 **ei tohi** olla avaldatud avalikku võrku ega üldse väljapoole klastri sisevõrku;
> - võrgureegel (NetworkPolicy / security group) peab lubama sissetuleva ühenduse **ainult** oma
>   turvaserveri aadressilt;
> - turvaserver ise peab olema ainus tee adapterini — ükski pöördproksi ega ingress ei tohi
>   `/xroad/*` teed välja tuua.
>
> `compose.override.yml` avaldab 8087 `localhost`-i, aga see fail on **ainult lokaalseks
> arenduseks** (`compose.yml` ise porti ei avalda). Tootmises tohib seda porti avaldada ainult
> turvaserverile.
>
> Alternatiiv, mis sellest sõltuvusest vabaneks, oleks mTLS ka turvaserveri ja adapteri vahel, aga
> X-Roadi juurutusmudel seda ei eelda ja RIA seda ei nõua.

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
admin-liidese *lugemine* `admin/GET/v1/audit.yml`), ja `ruuter-xroad.yaml` seab
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
| `X-Road-Id` | jah | Sõnumi id; logitakse korrelatsiooni jaoks |
| `X-Road-Service` | ei | Turvaserveri seatud; informatiivne |
| `X-Road-UserId` | ei | Lõppkasutaja isikukood. **Ainult audit — õigusi ei anna.** |
| `X-Road-Represented-Party`, `X-Road-Issue` | ei | Informatiivsed |

## Kontroll (`DSL/Ruuter-xroad/xroad/{GET,POST}/v1/.guard.yml`)

Guard **autendib ainult** — alamhulkade kontrolli ta ei tee (vt allpool):

1. `X-Road-Client` päis puudub või on vigane → **401** `unauthorized`
2. `X-Road-Id` päis puudub → **400** `missing-required-header`
3. `get_authority_by_registry_code` ei leia ühtegi `ACTIVE` asutust → **403** `forbidden`
4. Sama registrikoodiga on **rohkem kui üks** `ACTIVE` asutus → **403** `forbidden`
   (registri seadistusviga; suvalise rea valimine annaks ühele organisatsioonile teise õigused)
5. Muidu lubatud

`X-Road-Client` on kredentsiaal, seega selle puudumine on 401 mitte 403 — sama hoiak nagu
platvormide `X-Api-Key` guardil (ADR-004).

**Keeldumine on läbikukkumisharu** ja iga lubav tee on selgesõnaline positiivne tingimus. See
järjekord on kriitiline: kui ReSql tagastab mitte-massiiv keha (500 veaobjekt, parameetri
valideerimise viga), on `body.length` `undefined` ja kõik võrdlused sellega on `false` — päring peab
sattuma keeldumisele, mitte lubamisele. Ühe negatiivse tingimusega kirjutatud kontroll, mille
läbikukkumisharu on `guard_success`, kukub täpselt selle sisendi peal **lahti** (fail-open).

Guard on **kahes failis** (`GET/v1/` ja `POST/v1/`), **loogika identne** (kommentaarid erinevad).
Ruuteri guardid on kataloogipõhised ja puu on meetodipõhine, seega üks fail ei kata mõlemat
meetodit. Ilma `GET/v1/.guard.yml`-ita oleks `GET /xroad/v1/*` täiesti avalik, hoolimata sellest et
`ruuter-xroad.yaml` loetleb GET-i `guards.enforce_on_methods` all.

### Alamhulkade kontroll (`FORBIDDEN_SUBSET`) ei ole veel teostatud

`authorities.subsets` on **autoriseerimise allikas** ja `FORBIDDEN_SUBSET` on veakataloogis olemas,
aga **mitte ükski guard ega X-Roadi marsruut ei tee praegu alamhulga kontrolli** — X-Roadi pinnal ei
ole ühtegi marsruuti, mis alamhulga parameetrit vastu võtaks (olemas on ainult `echo` ja `subsets`).
Kui selline marsruut lisatakse, peab ta kontrolli ise tegema; guard seda tema eest ei tee.

Kontroll kuulub SQL-i, mitte DSL-i: Postgresi massiivi sisalduvuse operaator
(`requested <@ authorities.subsets`) teeb selle ühe avaldisega, samas kui ükski Ruuteri DSL fail
repos ei kasuta `.every` / `.includes` / noolefunktsioone, seega mootori JS-massiivi tugi on
tõestamata. ReSql juba oskab `{ type: array }` parameetreid ja `::text[]` teisendusi
(`insert_authority.sql`).

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
tehtud**: `xroad/POST/internal/` alla jääv marsruut oleks väljaspool kõiki guarde ja pordilt 8087
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
  `ee-adapter`/`core`, mida ei ole olemas; tegelik teostus on eraldi Ruuteri projekt
  `DSL/Ruuter-xroad/`.
- `docs/specs/permissions-matrix.md` §3.2 ja §7 — `FORBIDDEN_SUBSET` allikas.
- `docs/specs/openapi.yaml` — `User.subsets` kirjeldab veergu, mida ei ole.

`docs/specs/data-transformations.md` §428 ütles juba varem `requested subsets ⊆
authorities.subsets` — see oli õige ja jääb muutmata.

## Avatud küsimused

- **Auditikirjutaja puudub.** `X-Road-UserId` (ja üldse iga autoriseerimise sündmus) tuleks
  `audit_log` tabelisse kirjutada GDPR art 30 nõude täitmiseks — `logging-spec.md` kirjeldab välju,
  aga kirjutavat koodi ei ole kusagil. Kuni seda ei ole, on käesoleva ADR-i auditiväide **kavatsus,
  mitte teostus**. Kehtib kogu värava kohta, mitte ainult X-Roadi kanali kohta.
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
  nõuab TIM-i JWT-d, mida `ruuter-xroad`-il ei ole, ja projektiülene `template:` ei tööta. Vajab
  sisemist teenusetokenit (`AGENTS.md` märgib sama vajadust G2G sisendi jaoks).
- **Alamhulkade jõustamine JWT/TARA teel** (`efti/POST/api/v1/authority/dataset.yml`) on
  disainiliselt blokeeritud, kuni `users` real ei ole ei `subsets` veergu ega seost asutusega.

## Seos `refactor/split-m2m-ruuter` haruga

See otsus on teostatud `dev` haru paigutuses. Harus `origin/refactor/split-m2m-ruuter` (ADR-005)
nimetatakse `ruuter-xroad` ümber `ruuter-m2m`-ks ja marsruudid liiguvad. Vastavus liitmise jaoks:

| `dev` | `refactor/split-m2m-ruuter` |
|---|---|
| `DSL/Ruuter-xroad/xroad/POST/v1/` | `DSL/Ruuter-m2m/m2m/POST/xroad/v1/` |
| `DSL/Ruuter-xroad/xroad/GET/v1/` | `DSL/Ruuter-m2m/m2m/GET/xroad/v1/` |
| `constants-xroad.ini` / `ruuter-xroad.yaml` | `constants-m2m.ini` / `ruuter-m2m.yaml` |
| `tests/authority/xroad-*.http` | `tests/http/xroad-*.http` |
