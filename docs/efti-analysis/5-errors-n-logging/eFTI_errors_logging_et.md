# Vigade ja logimise spetsifikatsioon

| | |
|---|---|
| **Autor** | Sten Viljus |
| **Ettevõte** | Askend Estonia OÜ |
| **Kontakt** | sten.viljus@askend.com |

## 1. Veakoodide loetelu

eFTI Gate PoC kasutab Klite raamistiku standardseid HTTP exception klasse. Eraldi ärilisi veakoode (nt `ERR-001`) **ei ole defineeritud** — vead tagastatakse HTTP staatuskoodide ja vabatekstiliste sõnumitega.

### 1.1 Klite exception klassid ja nende HTTP mapping

| Exception klass | HTTP staatuskood | Millal tekib |
|----------------|-----------------|--------------|
| `UnauthorizedException` | 401 | Autentimata päring — `Authorization` header puudub või on vigane |
| `ForbiddenException` | 403 | Autenditud kasutajal puudub nõutud roll või ligipääs ressursile |
| `BadRequestException` | 400 | Vigane sisenand — XML parsimisviga, duplikaat request ID |
| `StatusCodeException(BadGateway)` | 502 | Sihtgate on offline või platvorm ei vasta |
| `StatusCodeException(InternalServerError)` | 500 | Sisemine viga — nt eDelivery ühenduse katkestus |
| `NoSuchElementException` | 500 | Kasutajat ei leitud (UserRepository) |
| `IllegalStateException` (via `error()`) | 500 | Tundmatu gate, platvorm või authority ID; puuduv annotatsioon |
| `IllegalArgumentException` (via `require()`) | 500 | Lubamatu kasutaja kustutamine; subset'id ei vasta authority omadele |

### 1.2 Ärilised veaolukorrad

| # | Veaolukord | Komponent | Exception | HTTP kood | Sõnum | Lahendus |
|---|-----------|-----------|-----------|-----------|-------|---------|
| 1 | Duplikaat request ID | `RequestIdValidator` | `BadRequestException` | 400 | `Request Id 'X' already used` | Genereerida unikaalne `X-Request-ID` header iga päringu jaoks (UUID). Duplikaat tähendab, et sama request ID saadeti 10 min jooksul teistkordselt |
| 2 | Vigane identifier XML | `EftiService.saveIdentifiers()` | `BadRequestException` | 400 | `Error parsing identifiers: <parsimisviga>` | Kontrollida XML vastavust eFTI common dataset XSD skeemile. Veateade sisaldab konkreetset parsimisviga (nt puuduv element, vale tüüp) |
| 3 | Kasutajal puudub platvormi roll | `PlatformRoutes.before()` | `UnauthorizedException` | 401 | `User has no platform access` | Kasutajale tuleb Admin UI kaudu määrata PLATFORM roll koos platvormi Party ID-ga |
| 4 | Kasutajal mitu platvormi | `PlatformRoutes.before()` | `UnauthorizedException` | 401 | `User has more than one platform registered...` | Identifier'ite registreerimise API nõuab täpselt ühte platvormi rolli. Luua eraldi kasutaja iga platvormi jaoks või kasutada Admin API-t |
| 5 | Vigane Basic/Bearer token | `AccessChecker.before()` | `ForbiddenException` | 403 | `Invalid authorization (must be valid Basic or Bearer token)` | Kontrollida `Authorization` headeri formaati: Basic → `base64(email:parool)`, Bearer → `base64(userId:parool)`. Veenduda, et kasutaja eksisteerib ja parool on õige |
| 6 | Puuduv roll | `AccessChecker.checkAccess()` | `ForbiddenException` | 403 | (tühi sõnum) | Kasutajal puudub endpoint'ile ligipääsuks vajalik roll. Kontrollida kasutaja rolle Admin UI-s ja vajadusel lisada õige roll |
| 7 | Puuduv kirjutusõigus | `User.checkWriteAccess()` | `ForbiddenException` | 403 | `No access to <entityId>` | Kasutaja üritab muuta ressurssi, mis ei kuulu tema rollide alla. Kontrollida, et kasutaja `roles` sisaldab muudetava ressursi Party ID-d |
| 8 | Admin ei saa anda kõrgemaid rolle | `UserAdminRoutes.ensureAllowedRoles()` | `ForbiddenException` | 403 | (tühi sõnum) | Tavaline Admin saab luua kasutajaid ainult oma rollidega. Kõrgemate rollide määramiseks kasutada Super Admin kontot |
| 9 | Tundmatu gate ID | `GateRegistry.get()` | `IllegalStateException` | 500 | `Unknown gate: <id>` | Kontrollida gate ID-d. Gate peab olema eelnevalt registreeritud Admin UI kaudu. Veenduda, et ID kirjapilt on korrektne |
| 10 | Tundmatu platvormi ID | `PlatformRegistry.get()` | `IllegalStateException` | 500 | `No platform with id: <id>` | Kontrollida platvormi ID-d. Platvorm peab olema eelnevalt registreeritud Admin UI kaudu |
| 11 | Tundmatu authority ID | `AuthorityRegistry.get()` | `IllegalStateException` | 500 | `No authority with id <id>` | Kontrollida asutuse ID-d. Asutus peab olema eelnevalt registreeritud Admin UI kaudu |
| 12 | Gate offline | `EftiService.checkGateAvailable()` | `StatusCodeException(502)` | 502 | `Cannot reach Gate <id>: <status>` | Sihtgate ei ole kättesaadav. Kontrollida gate'i staatust Admin UI-s. Oota kuni gate tuleb tagasi ONLINE staatusesse (GatePingJob kontrollib automaatselt) |
| 13 | Platvormi ping ebaõnnestub | `PlatformClient.ping()` | `StatusCodeException` | platvormi kood | `Ping failed, code <code>` | Platvormi tervisekontroll ebaõnnestus. Kontrollida platvormi URL-i ja võrguühendust. HTTP kood viitab konkreetsele veale platvormi poolel |
| 14 | Gate'i ping ebaõnnestub (ühendus) | `GateClient.ping()` | `StatusCodeException(500)` | 500 | `Could not connect to URL` | Sihtgate'i URL ei ole kättesaadav. Kontrollida URL-i, DNS-i ja tulemüüri reegleid. Veenduda, et TLS sertifikaat on kehtiv |
| 15 | Gate'i ping ebaõnnestub (HTTP) | `GateClient.ping()` | `StatusCodeException` | gate'i kood | `Ping failed, code <code>` | Sihtgate vastas veakoodiga. Kontrollida gate'i logisid konkreetse vea tuvastamiseks |
| 16 | Platvormi ühenduse katkestus | `PlatformClient.sendRequest()` | (püütakse kinni) | 502 | Exception message | Platvormiga ühendus katkes päringu ajal. Kontrollida platvormi kättesaadavust ja timeout seadistusi. Proovida uuesti |
| 17 | Follow-up vale gate | `EftiService.handlePostFollowUpRequest()` | `IllegalStateException` | 500 | `Follow up gateId does not match this gate Ids` | Follow-up sõnumi `gateId` ei vasta sellele gate'ile. Kontrollida UIL-i (gateId/platformId/datasetId) — follow-up tuleb saata õigele gate'ile |
| 18 | Kasutajat ei leitud | `UserRepository.save()` | `NoSuchElementException` | 500 | `User not found: <id>` | Kasutaja UUID ei eksisteeri andmebaasis. Võimalik, et kasutaja kustutati samaaegselt. Laadida kasutajate nimekiri uuesti |
| 19 | Kasutaja kustutamine keelatud | `UserAdminRoutes.deleteUser()` | `IllegalArgumentException` | 500 | `Not allowed to delete that user` | Admin ei saa kustutada iseennast ega kasutajaid väljaspool oma rollide ulatust |
| 20 | Subset'id ei vasta authority omadele | `UserAdminRoutes.checkAuthorityUserSubsets()` | `IllegalArgumentException` | 500 | `Subsets must match Authority's subsets` | Kasutajale määratud subsetid peavad olema Authority enese subsettide alamhulk. Kontrollida Authority subsettide konfiguratsiooni |
| 21 | eDelivery sõnumi töötlemise viga | `EDeliveryRoutes.msh()` | (püütakse kinni) | 500 | SOAP Fault XML | eDelivery AS4 sõnumi töötlemisel tekkis viga. Kontrollida saatja sertifikaati, sõnumi formaati ja krüpteerimist. SOAP Fault sisaldab konkreetset veateavet |
| 22 | Vale KeyIdentifier | `EDeliveryRoutes.decryptPayload()` | `IllegalArgumentException` | 500 | `Invalid KeyIdentifier "<x>", expected "<y>"` | Saatja krüpteeris sõnumi vale sertifikaadiga. Saatja peab kasutama vastuvõtja kehtivat eDelivery sertifikaati (SKI peab klappima) |

### 1.3 Tähelepanekud

- **`IllegalStateException` ja `IllegalArgumentException` tagastavad 500** — need on Kotlin `error()` ja `require()` viskamised. Tootmises peaks osa neist olema `BadRequestException` (400) või `NotFoundException` (404), mitte 500.
  - Näiteks `Unknown gate: <id>` ja `No platform with id: <id>` peaks tagastama **404**, mitte 500.
  - `Subsets must match Authority's subsets` ja `Not allowed to delete that user` peaks tagastama **400** või **403**, mitte 500.
- **eDelivery vead tagastatakse SOAP Fault XML-ina**, mitte JSON-ina — see on eDelivery AS4 protokolli nõue.
- **TODO koodis:** `EftiService.checkGateAvailable()` — `// TODO: in XML api, render errors either as plain text or xml`.

---

## 2. HTTP staatused endpoint'ide kaupa

### 2.1 eFTI REST API (`/v1`)

| Endpoint | Meetod | Edukas | Autentimata | Keelatud | Vigane sisend | Siht offline | Sisemine viga |
|----------|--------|--------|-------------|----------|---------------|-------------|---------------|
| `/v1/identifiers/:identifier` | GET | 200 | 401 | 403 | 400¹ | — | 500 |
| `/v1/dataset/:gateId/:platformId/:datasetId` | GET | platvormi kood² | 401 | 403 | 400¹ | 502 | 500 |
| `/v1/follow-up/:gateId/:platformId/:datasetId/:datasetRequestId` | POST | 200 | 401 | 403 | 400¹ | 502 | 500 |
| `/v1/consignments/identifier/:datasetId` | POST | 200 | 401 | 403 | 400 | — | 500 |

¹ Duplikaat request ID (`RequestIdValidator`)
² Dataset päringu korral tagastatakse platvormi vastuse staatuskood läbipaistvalt — eduka päringu korral tavaliselt 200

### 2.2 Admin API (`/api`)

| Endpoint | Meetod | Edukas | Autentimata | Keelatud |
|----------|--------|--------|-------------|----------|
| `/api/user` | GET | 200 | 401 | 403 |
| `/api/switch` | GET | 200 / 401³ | 401 | — |
| `/api/gates` | GET | 200 | 401 | 403 |
| `/api/gates` | POST | 200 | 401 | 403 |
| `/api/gates/:gateId` | DELETE | 200 | 401 | 403 |
| `/api/gates/:gateId/ping` | POST | 200 | 401 | 403⁴ |
| `/api/platforms` | GET | 200 | 401 | 403 |
| `/api/platforms` | POST | 200 | 401 | 403 |
| `/api/platforms/:platformId` | DELETE | 200 | 401 | 403 |
| `/api/platforms/:platformId/ping` | POST | 200 | 401 | 403⁴ |
| `/api/authorities` | GET | 200 | 401 | 403 |
| `/api/authorities/:authorityId` | GET | 200 | 401 | 403 |
| `/api/authorities` | POST | 200 | 401 | 403 |
| `/api/authorities/:authorityId` | DELETE | 200 | 401 | 403 |
| `/api/users` | GET | 200 | 401 | 403 |
| `/api/users` | POST | 200⁵ | 401 | 403 |
| `/api/users/:userId` | DELETE | 200 | 401 | 403 |
| `/api/consignments` | GET | 200 | 401 | 403 |
| `/api/consignments/:datasetId` | DELETE | 200 | 401 | 403 |

³ `AdminAuthRoutes.userSwitch()` — kasutab 401 tahtlikult kasutaja vahetamiseks (Basic Auth re-prompt)
⁴ Ping võib tagastada 500 (ühenduse katkestus) või 502 (gate/platvorm offline)
⁵ Kui `generateSecret=true`, tagastab genereeritud secret'i (`base64(id:password)` või plain password)

### 2.3 eDelivery endpoint'id (`/services`)

| Endpoint | Meetod | Edukas | Viga |
|----------|--------|--------|------|
| `/services/msh` | GET | 200 | — |
| `/services/msh` | POST | 200 + SOAP vastus | 500 + SOAP Fault |
| `/services/fast` | POST | 200 + XML vastus | 500 |

### 2.4 Muud endpoint'id

| Endpoint | Meetod | Edukas |
|----------|--------|--------|
| `/health` | GET | 200 (`OK`) |
| `/metrics` | GET | 200 (JSON meetrikad) |

---

## 3. Veasõnumi formaat

### 3.1 Praegune formaat (PoC)

Gate'il **puudub ühtne veavormingu standard**. Veateated tagastatakse erineval kujul sõltuvalt kontekstist:

**REST API vead — plain text:**
```
HTTP 400
Request Id 'abc-123' already used
```

```
HTTP 403
Invalid authorization (must be valid Basic or Bearer token)
```

```
HTTP 502
Cannot reach Gate gate-fi1: OFFLINE
```

**eDelivery vead — SOAP Fault XML:**
```xml
HTTP 500
<env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
  <env:Header/>
  <env:Body>
    <env:Fault>
      <env:Code><env:Value>env:Receiver</env:Value></env:Code>
      <env:Reason><env:Text xml:lang="en">Failed to process eDelivery message: <veateade></env:Text></env:Reason>
    </env:Fault>
  </env:Body>
</env:Envelope>
```

**Dataset vastuse vead — XML wrapper:**
```xml
<uilResponse xmlns="http://efti.eu/v1/edelivery" requestId="abc-123" status="502">
  <description>Cannot reach Gate gate-fi1: OFFLINE</description>
</uilResponse>
```

### 3.2 Tähelepanekud

- **Puudub ühtne JSON veavormimng** — REST API vead tulevad plain text'ina, mis raskendab klientpoolset veakäsitlust.
- **Puudub request ID veavastuses** — viga ei ole korreleeritav päringu logiga.
- **Puudub veakoodi süsteem** — klient peab sõltuma HTTP staatuskoodist ja vabatekstilisest sõnumist.

### 3.3 Ettepanek: ühtne veavormimng

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Request Id 'abc-123' already used",
  "requestId": "550e8400-e29b/abc-123",
  "timestamp": "2026-03-12T10:30:00Z"
}
```

eDelivery endpoint'id jätkaksid SOAP Fault formaadiga (AS4 protokolli nõue).

---

## 4. Logimisreeglid

### 4.1 Logimise raamistik

- **SLF4J** Klite wrapper'i kaudu (`klite.logger()`)
- **Logitasemed:** `INFO`, `WARN`, `ERROR`, `DEBUG`
- **Request ID:** Klite `UUIDRequestIdGenerator` genereerib `internalId/externalRequestId` formaadis. Request ID on lõime nimes, aga **ei propageeru MDC kaudu logisõnumitesse**.

### 4.1b Kohustuslikud logiväljad (nõue)

Vastavalt KeMIT MFN (v1.2.0) ja tehnilise kirjelduse nõuetele peab iga logisõnum sisaldama järgmisi välju. Logid peavad olema **JSON formaadis** vastavalt **Elastic Common Schema (ECS)** standardile (vt [ECS dokumentatsioon](https://www.elastic.co/guide/en/ecs/current/index.html)).

| Väli | ECS väli | Kirjeldus | Praegune seis |
|------|----------|-----------|---------------|
| **Ajatempel** | `@timestamp` | ISO 8601 UTC formaat | ✅ SLF4J lisab automaatselt |
| **Logitase** | `log.level` | DEBUG, INFO, WARN, ERROR | ✅ Kasutusel |
| **Päringu ID** | `trace.id` | `X-Request-ID` header / genereeritud UUID, korreleeritav kogu päringu elutsükli jooksul | ⚠️ Olemas lõime nimes, aga ei propageeru MDC kaudu logisõnumitesse |
| **Teenus** | `service.name` | Rakenduse nimi (nt `efti-gate`) | ❌ Puudub, tuleb lisada logback konfiguratsiooni |
| **Kasutaja ID** | `user.id` | Autenditud kasutaja UUID | ❌ Puudub logisõnumitest |
| **Kasutaja roll** | `user.roles` | Kasutaja rollid (ADMIN, GATE, PLATFORM, AUTHORITY) | ❌ Puudub logisõnumitest |
| **Endpoint** | `url.path` | HTTP meetod + tee (nt `GET /v1/identifiers/ABC-123`) | ✅ RequestLogger logib |
| **Sõnum** | `message` | Logisõnumi sisu (inglise keeles) | ✅ Kõik komponendid |
| **Kliendi IP** | `client.ip` | Päringu lähte-IP | ❌ Puudub |
| **Vastuse kood** | `http.response.status_code` | HTTP staatuskood | ✅ RequestLogger logib |
| **Kestus (ms)** | `event.duration` | Päringu töötlemise aeg millisekundites | ⚠️ Ainult RequestLogger ja PlatformClient (REST) |

**Viited:**
- KeMIT MFN v1.2.0, ptk "Logimine ja monitooring": logisõnumid inglise keeles, JSON formaat, ECS standard, kasutaja ja rolliga seostatuna, tundlikud andmed logidest välja
- KeMIT MFN v1.2.0, ptk "Observability": [https://wiki.kemit.ee/spaces/MFN/pages/289855884/Observability](https://wiki.kemit.ee/spaces/MFN/pages/289855884/Observability)
- Tehniline kirjeldus ptk 9.5: kohustuslikud väljad (timestamp, level, requestId, service, userId, endpoint, message), JSON formaat, päringute korrelatsioon

**Tundlike andmete keeld** (KeMIT MFN + GDPR): logidesse **ei tohi** sattuda paroole, token'eid, isikukoode, krediitkaardi numbreid ega muid tundlikke isikuandmeid. Praegu on see nõue täidetud.

### 4.2 Olemasolev logimise katvus komponentide kaupa

| Komponent | Fail | Logib edukaid | Logib vigu | Logib kestust | Logib sihtpunkti |
|-----------|------|:---:|:---:|:---:|:---:|
| **RequestLogger** | `GateLauncher.kt` | ✅ | ✅ | ✅ | — (sissetulev) |
| **PlatformClient** (REST) | `PlatformClient.kt` | ✅ | ✅ | ✅ | ✅ |
| **PlatformClient** (eDelivery) | `PlatformClient.kt` | ❌ | ❌ | ❌ | ❌ |
| **GateClient** | `GateClient.kt` | ❌ | ✅ (ainult ping) | ❌ | ❌ |
| **EDeliveryClient** | `EDeliveryClient.kt` | ❌ | ❌ | ❌ | ❌ |
| **EDeliveryRoutes** | `EDeliveryRoutes.kt` | ❌ | ✅ | ❌ | — (sissetulev) |
| **GateMessageHandler** | `GateMessageHandler.kt` | ✅ | ✅ | ❌ | — (sissetulev) |
| **EftiService** | `EftiService.kt` | ❌ | ✅ (broadcast) | ❌ | ❌ |
| **AccessChecker** | `AccessChecker.kt` | ❌ | ✅ | ❌ | — |
| **GatePingJob** | `GatePingJob.kt` | ✅ | ✅ | ❌ | ❌ |
| **IdentifierExpirationJob** | `IdentifierExpirationJob.kt` | ✅ | — | — | — |
| **KeyManager** | `KeyManager.kt` | ✅ | — | — | — |
| **MultiNodeAsyncResponseProvider** | `MultiNodeAsyncResponseProvider.kt` | ✅ | — | ❌ | — |

### 4.3 Sissetulevate päringute logimine

**RequestLogger** (`GateLauncher.kt` rida 39–41) logib iga sissetuleva HTTP päringu:

```kotlin
register<RequestLogger>(RequestLogger { ms ->
    "<" + attr<String?>("client") + "> " + defaultRequestLogFormatter(ms)
})
```

Formaat:
```
<klient> METHOD /path - statusCode XXms
```

`client` atribuut seatakse:
- `AuthorityRoutes.before()` — asutuse ID (`authorityId` rollist) või kasutaja email
- `PlatformRoutes.before()` — platvormi ID (esimene `PLATFORM` roll)
- `EDeliveryRoutes.msh()` — eDelivery saatja Party ID (XML-ist parsitud)
- Kui pole seatud — `null`

Näidisväljund:
```
<eu-authority-1> GET /v1/identifiers/ABC-123 - 200 12ms
<demo-platform> POST /v1/consignments/identifier/uuid - 200 8ms
<gate-fi1> POST /services/msh - 200 45ms
<null> GET /health - 200 1ms
```

### 4.4 Väljaminevate päringute logimine

**PlatformClient** (REST) on ainuke komponent, mis logib väljaminevaid päringuid korrektselt:

```kotlin
private fun log(platform: Platform, request: HttpRequest, start: Long, response: HttpResponse<String>? = null, e: Exception? = null) =
    log.info("${platform.id}: ${request.method()} ${request.uri()} - ${response?.statusCode() ?: e?.toString()} ${currentTimeMillis() - start} ms")
```

Näidisväljund:
```
demo-platform: GET https://platform.example/v1/dataset/uuid?subsetId=... - 200 45 ms
demo-platform: GET https://platform.example/v1/dataset/uuid?subsetId=... - java.net.ConnectException: Connection refused 5003 ms
```

**GateClient** — logger on deklareeritud, aga kasutusel ainult ping vea korral:
```kotlin
log.error("Could not ping gate", e)
```

Logimata meetodid: `getIdentifiers()`, `getDataset()`, `postFollowUp()`, `sendAndReceive()`.

**EDeliveryClient** — loggerit **ei kasutata**. Ainult meetrika counter `edelivery_messages_sent`.

### 4.5 eDelivery sõnumite vastuvõtmine

**EDeliveryRoutes** logib ainult vigu ja hoiatusi:

| Olukord | Tase | Näide |
|---------|------|-------|
| Tundmatu saaja | `WARN` | `Unknown receiver: gate-xx1` |
| Tundmatu võtme krüpteerimismeetod | `WARN` | `Unknown key encryption method: http://...` |
| Tundmatu andmete krüpteerimismeetod | `WARN` | `Unknown data encryption method: http://...` |
| Sõnumi töötlemise viga | `ERROR` | `Error when processing message: <veateade>. Raw content: <sõnum>` |

**GateMessageHandler** logib iga sissetuleva eDelivery sõnumi tüübi ja saatja:
```
Handling uilQuery from RequestKey(senderId=gate-fi1, requestId=abc-123, receiverId=eu-ee31).
```
Vea korral logitakse ka kogu XML payload.

### 4.6 Äriloogika logimine

**EftiService** logib ainult broadcast vea korral:
```kotlin
log.warn("${gate.id} failed with $e for $q")
```

Logimata: `saveIdentifiers()`, `getDataset()` routing otsus, `getIdentifiers()` broadcast algus/koondtulemus, `sendFollowUp()` suunamine, `handleUilQuery()`, `handleIdentifierQuery()`, `handlePostFollowUpRequest()`.

### 4.7 Autentimine

**AccessChecker** logib ainult ebaõnnestunud autentimisi (`log.error` exception'iga). Edukad autentimised, autoriseerimise kontrollid ja rollide valideerimised **ei ole logitud**.

### 4.8 Taustatööd

**GatePingJob:**
- Ping'i ebaõnnestumine: `Gate ${gate.id} ping failed: ${e.message}`
- Staatuse muutus: `Gate ${gate.id} status changed: ${gate.status} -> $newStatus`
- Uuendatud gate'ide arv: `Updated status for $updatedCount gates`

**IdentifierExpirationJob:**
- Kustutatud kirjed: `Removed $count expired identifiers`

### 4.9 Krüptograafia

**KeyManager** logib käivitusel:
- Oma Party ID ja sertifikaadi SKI
- Iga sertifikaadi nimi ja SKI
- TrustStore ehitamine (vaikimisi + kohandatud sertifikaatide arv)

### 4.10 Asünkroonne vastuste käsitlemine

**MultiNodeAsyncResponseProvider** (`MultiNodeAsyncResponseProvider.kt`) logib:
- Vastuse ootamise algus: `Waiting for response for $key`
- Vastuse salvestamine DB-sse (teise node'i jaoks): `Inserting response for $requestKey`

**Hinnang:** ✅ Hea — piisav async flow debugimiseks.

### 4.11 Meetrikad

| Meetrika | Komponent | Kirjeldus |
|----------|-----------|-----------|
| `edelivery_messages_sent` | `EDeliveryClient` | Saadetud eDelivery sõnumite koguarv |
| `edelivery_messages_received` | `EDeliveryRoutes` | Vastu võetud eDelivery sõnumite koguarv |
| `edelivery_client` | `EDeliveryClient` | HTTP kliendi seis: pendingRequests, openedConnections, pendingOperationCount |

Meetrikad on kättesaadavad `/metrics` endpoint'ist.

### 4.12 Üldine hinnang

Praegune logimine vastab **PoC tasemele**:
- Kriitilised vead logitakse
- `RequestLogger` annab ülevaate sissetulevatest päringutest
- `PlatformClient` (REST) on eeskujulik näide, kuidas väljaminevaid päringuid logida

Küsimusele **"kust tehti päring, kuhu tehti, mis tulemus oli"** vastab korralikult ainult `PlatformClient` (REST variant). Ülejäänud väljaminevate päringute kohta (GateClient, EDeliveryClient) puudub info täielikult.

**Tootmiskeskkonna jaoks ei ole praegune logimise tase piisav.**

---

## 5. Auditnõuded

### 5.1 Praegune seis

Auditi logimine on **minimaalne** — logitakse ainult ebaõnnestunud autentimised (`AccessChecker.log.error`). GDPR ja auditi seisukohast vajalikud sündmused **ei ole logitud**.

### 5.2 Audiditavad sündmused (ettepanek)

| # | Sündmus | Logitase | Vajalik info | Praegune seis |
|---|---------|----------|-------------|---------------|
| A1 | **Edukas sisselogimine** | INFO | Kasutaja ID, email, roll, IP-aadress, autentimismeetod (Basic/Bearer) | ❌ Puudub |
| A2 | **Ebaõnnestunud sisselogimine** | WARN | IP-aadress, kasutajanimi (kui oli), põhjus | ✅ Olemas (`AccessChecker.log.error`) |
| A3 | **Kasutaja loomine** | INFO | Loodud kasutaja ID, email, rollid, admin kasutaja ID | ❌ Puudub |
| A4 | **Kasutaja muutmine** | INFO | Muudetud kasutaja ID, muudetud väljad, admin kasutaja ID | ❌ Puudub |
| A5 | **Kasutaja kustutamine** | INFO | Kustutatud kasutaja ID, admin kasutaja ID | ❌ Puudub |
| A6 | **Gate'i lisamine/muutmine** | INFO | Gate ID, admin kasutaja ID | ❌ Puudub |
| A7 | **Gate'i kustutamine** | INFO | Gate ID, admin kasutaja ID | ❌ Puudub |
| A8 | **Platvormi lisamine/muutmine** | INFO | Platvormi ID, admin kasutaja ID | ❌ Puudub |
| A9 | **Platvormi kustutamine** | INFO | Platvormi ID, admin kasutaja ID | ❌ Puudub |
| A10 | **Authority lisamine/muutmine** | INFO | Authority ID, admin kasutaja ID | ❌ Puudub |
| A11 | **Authority kustutamine** | INFO | Authority ID, admin kasutaja ID | ❌ Puudub |
| A12 | **Identifier'ite otsing** | INFO | Otsija (authority ID), otsinguparameetrid, tulemuste arv | ❌ Puudub |
| A13 | **Dataset'i pärimine** | INFO | Pärija (authority ID), UIL, subsets | ❌ Puudub |
| A14 | **Follow-up sõnumi saatmine** | INFO | Saatja (authority ID), UIL, sõnumi pikkus | ❌ Puudub |
| A15 | **Identifier'ite salvestamine** | INFO | Platvormi ID, dataset ID, identifier'ite arv | ❌ Puudub |
| A16 | **Consignment'i kustutamine** | INFO | Dataset ID, admin kasutaja ID | ❌ Puudub |

### 5.3 GDPR nõuded

- **Andmetele ligipääsu logimine** (A12, A13) — eFTI andmed sisaldavad kaubaveo infot, mis võib olla seotud isikutega. Iga ligipääs peab olema jälgitav.
- **Säilitustähtaeg** — auditlogide säilitustähtaeg peab olema kooskõlas GDPR nõuetega (tavaliselt 1–5 aastat).
- **Logide kaitse** — auditlogid peavad olema muutmatud ja kaitstud loata ligipääsu eest.

---

## 6. Turvalogid

### 6.1 Praegune seis

| # | Turvasündmus | Logitakse? | Komponent | Logitase |
|---|-------------|:---:|-----------|----------|
| T1 | Ebaõnnestunud autentimine | ✅ | `AccessChecker` | ERROR |
| T2 | Edukas autentimine | ❌ | — | — |
| T3 | Autoriseerimise keeldumine (puuduv roll) | ❌ | `AccessChecker` | (viskab ForbiddenException, ei logi) |
| T4 | Kirjutusõiguse keeldumine | ❌ | `User.checkWriteAccess()` | (viskab ForbiddenException, ei logi) |
| T5 | Tundmatu eDelivery saaja | ✅ | `EDeliveryRoutes` | WARN |
| T6 | Vale KeyIdentifier eDelivery sõnumis | ✅ | `EDeliveryRoutes` | ERROR (via require) |
| T7 | Tundmatu krüpteerimismeetod | ✅ | `EDeliveryRoutes` | WARN |
| T8 | eDelivery sõnumi töötlemise viga | ✅ | `EDeliveryRoutes` | ERROR |
| T9 | Gate'i ühenduse katkestus | ✅ | `GateClient` | ERROR |
| T10 | Duplikaat request ID | ❌ | `RequestIdValidator` | (viskab BadRequestException, ei logi) |

### 6.2 Puuduvad turvalogid (ettepanek)

| # | Turvasündmus | Soovitav logitase | Prioriteet | Kirjeldus |
|---|-------------|-------------------|------------|-----------|
| T3 | **Autoriseerimise keeldumine** | WARN | KÕRGE | Praegu `AccessChecker` viskab `ForbiddenException`, aga **ei logi seda** — keeldumised ei ole logides nähtavad. Tuvastamise aluseks brute force ja privilege escalation katsete puhul |
| T11 | **Korduv ebaõnnestunud autentimine samalt IP-lt** | WARN | KÕRGE | Praegu puudub — vajalik brute force tuvastamiseks |
| T2 | **Edukas autentimine** | INFO | KESKMINE | Kes logis sisse, milliselt IP-lt, milline roll. Vajalik audit trail'i jaoks |
| T4 | **Kirjutusõiguse keeldumine** | WARN | KESKMINE | `User.checkWriteAccess()` viskab `ForbiddenException`, aga ei logi — lubamatud muutmiskatsed pole jälgitavad |
| T10 | **Duplikaat request ID** | WARN | MADAL | Võib viidata replay-rünnakule |

### 6.3 Tähelepanekud

- **`AccessChecker` logib ebaõnnestunud autentimisi `log.error`'iga**, aga **ei logi autoriseerimise keeldumisi** (`ForbiddenException` visatakse, aga ei logita). See tähendab, et keegi, kes üritab ligipääsu ressursile, millele tal pole õigust, **jääb logidesse märkamatuks**.
- **`RequestIdValidator` ei logi duplikaat request ID-sid** — viskab ainult `BadRequestException`. Logisse jõuab küll 400 vastus `RequestLogger`'i kaudu, aga konkreetne põhjus (duplikaat ID) ei ole nähtav.

---

## 7. Näidisstsenaariumid

### 7.1 Identifier'ite otsing (edukas)

**Stsenaarium:** Authority otsib identifikaatorit "ABC-123", andmed on lokaalselt olemas.

```
1. → Sissetulev päring
   RequestLogger: <eu-authority-1> GET /v1/identifiers/ABC-123 - 200 15ms

2. Äriloogika (praegu EI LOGITA)
   - EftiService.getIdentifiers() — lokaalse otsingu tulemus: 2 consignment'i
   - Broadcast ei käivitu (andmed olemas lokaalselt)

3. ← Vastus: 200 OK + JSON/SSE
```

**Puudu logidest:** Mitu tulemust leiti, kas broadcast käivitus, kaua otsing kestis.

### 7.2 Dataset'i pärimine teiselt gate'ilt (edukas)

**Stsenaarium:** Authority pärib dataset'i, mis asub Soome gate'is.

```
1. → Sissetulev päring
   RequestLogger: <eu-authority-1> GET /v1/dataset/gate-fi1/platform-fi/uuid?subsetId=S1,S2 - 200 1250ms

2. Äriloogika (praegu EI LOGITA)
   - EftiService.getDataset() — routing: remote gate gate-fi1

3. Gate'idevahelise suhtlus (praegu EI LOGITA)
   - GateClient.sendAndReceive() → gate-fi1 (eDelivery)
   - EDeliveryClient.send() → https://gate-fi1.example/services/msh

4. eDelivery vastuse vastuvõtmine
   GateMessageHandler: Handling uilResponse from RequestKey(senderId=gate-fi1, requestId=abc-123, receiverId=eu-ee31).

5. ← Vastus: 200 OK + XML dataset
```

**Puudu logidest:** Routing otsus (lokaalne vs remote), eDelivery sihtpunkt ja kestus, GateClient'i tulemus.

### 7.3 Autentimise ebaõnnestumine

**Stsenaarium:** Keegi üritab vigase Bearer tokeniga ligi pääseda.

```
1. → Sissetulev päring
   AccessChecker: log.error — java.lang.IllegalArgumentException: Invalid UUID string: xxx

2. ← Vastus: 403 Forbidden
   RequestLogger: <null> GET /v1/identifiers/ABC-123 - 403 2ms
```

**Puudu logidest:** IP-aadress, milline token oli (hashi kujul), korduva ebaõnnestumise hoiatus.

### 7.4 Platvormi identifier'ite salvestamine (vigane XML)

**Stsenaarium:** Platvorm saadab vigase XML-i.

```
1. → Sissetulev päring
   RequestLogger: <demo-platform> POST /v1/consignments/identifier/uuid - 400 5ms

2. Äriloogika
   EftiService.saveIdentifiers() → viskab BadRequestException("Error parsing identifiers: ...")

3. ← Vastus: 400 Bad Request + plain text veateade
```

**Puudu logidest:** Milline parsimisviga täpselt tekkis (jõuab ainult vastusesse, mitte logisse).

### 7.5 eDelivery sõnumi vastuvõtmine (krüpteerimisviga)

**Stsenaarium:** Teiselt gate'ilt tuleb sõnum, mille KeyIdentifier ei klapi.

```
1. → Sissetulev päring
   RequestLogger: <gate-de1> POST /services/msh - 500 8ms

2. eDelivery töötlus
   EDeliveryRoutes: ERROR Error when processing message: Invalid KeyIdentifier "xxx", expected "yyy". Raw content: <kogu sõnum>

3. ← Vastus: 500 + SOAP Fault XML
```

### 7.6 Gate offline (dataset päring)

**Stsenaarium:** Authority pärib dataset'i gate'ilt, mis on offline.

```
1. → Sissetulev päring
   RequestLogger: <eu-authority-1> GET /v1/dataset/gate-fi1/platform-fi/uuid?subsetId=S1 - 502 1ms

2. Äriloogika
   EftiService.checkGateAvailable() → viskab StatusCodeException(502, "Cannot reach Gate gate-fi1: OFFLINE")

3. ← Vastus: 502 Bad Gateway + plain text "Cannot reach Gate gate-fi1: OFFLINE"
```

---

## 8. Tugevused

1. **RequestLogger** katab automaatselt kõik sissetulevad HTTP päringud koos kliendi identifikaatoriga
2. **PlatformClient** (REST) on eeskujulik — logib sihtpunkti, tulemust ja kestust ühes real
3. **GateMessageHandler** logib sissetuleva sõnumi tüübi ja saatja — hea ülevaade eDelivery liiklusest
4. **GatePingJob** logib staatuse muutused — piisav gate'ide kättesaadavuse jälgimiseks
5. **KeyManager** logib sertifikaatide info — aitab debugida krüptograafia probleeme
6. **MultiNodeAsyncResponseProvider** logib async vastuste voogu — aitab debugida mitme node'i sünkroonimist
7. **Kliendi identifikaator** on RequestLogger'is alati olemas (authority ID, platform ID, eDelivery sender ID)

---

## 9. Puudused

| # | Puudus | Tase | Mõju |
|---|--------|------|------|
| 1 | **GateClient ei logi väljaminevaid päringuid** | KÕRGE | Gate'idevahelise suhtluse jälgimine on võimatu. Broadcast identifier query'd, remote dataset päringud ja follow-up sõnumid on logidest nähtamatud |
| 2 | **EDeliveryClient.send() ei logi** | KÕRGE | eDelivery sõnumite saatmine on täielikult jälgimatu — sihtpunkt, vastus ja kestus pole logitud |
| 3 | **Request ID ei propageeru logisõnumitesse** | KÕRGE | Päringuid ei saa logist korreleerida — ühe kasutaja päringu teekonda läbi süsteemi ei saa jälgida. Lõime nimi sisaldab request ID-d, aga see ei jõua alati logisõnumisse |
| 4 | **EftiService ei logi äriloogika voogusid** | KESKMINE | Routing otsused (lokaalne vs remote), operatsioonide algus/lõpp ja tulemused on nähtamatud |
| 5 | **Struktureeritud logimine puudub** | KESKMINE | Logid on vabas tekstiformaadis — masinloetav parsimine, filtreerimine ja monitooring on keeruline |
| 6 | **Edukad autentimised pole logitud** | KESKMINE | Auditi jaoks puudub info, kes ja millal sisse logis |
| 7 | **Autoriseerimise keeldumised pole logitud** | KESKMINE | `AccessChecker` viskab `ForbiddenException`, aga ei logi — turvaintsidendid jäävad märkamatuks |
| 8 | **PlatformClient eDelivery variant ei logi** | KESKMINE | Kui platform kasutab eDelivery'd (mitte REST-i), siis PlatformClient'i hea logimine ei rakendu |
| 9 | **Kestuse logimine puudub enamikust komponentidest** | KESKMINE | Ainult PlatformClient (REST) ja RequestLogger logivad kestust |
| 10 | **Ühtne veavormimng puudub** | KESKMINE | REST API vead tulevad plain text'ina, puudub request ID ja veakood vastuses |

---

## 10. Ettepanekud parendamiseks

### 10.1 Väljaminevate päringute logimine (prioriteet: KÕRGE)

Lisada `PlatformClient`'i sarnane logimismuster `GateClient`'ile ja `EDeliveryClient`'ile:

**GateClient — lisada logimine:**
- `sendAndReceive()` — logida gate ID, protokoll (Fast/eDelivery), siht-URL, tulemus, kestus
- `getIdentifiers()` — logida broadcast tulemus (mitu consignment'i leiti)
- `getDataset()` — logida remote dataset tulemus
- `postFollowUp()` — logida follow-up saatmine
- `ping()` — logida edukas ping

**EDeliveryClient — lisada logimine:**
- `send()` — logida siht-URL, saaja Party ID, request ID, vastuse staatuskood, kestus
- `sendAndReceive()` — logida async ootamise algus ja kestus
- `ping()` — logida ping sihtpunkt ja tulemus

**Soovitav logiformaat:**
```
GateClient: gate-fi1 (fast) POST https://gate-fi1.example/services/fast - 200 45ms
GateClient: gate-de1 (eDelivery) sendAndReceive https://gate-de1.example/services/msh - 200 1250ms
EDeliveryClient: send to gate-fi1 https://gate.example/services/msh - 200 89ms (requestId=abc-123)
```

### 10.2 Request ID propageerimine (prioriteet: KÕRGE)

Praegu `UUIDRequestIdGenerator` genereerib request ID ja paneb selle lõime nimeks. Soovitus: kasutada SLF4J MDC (Mapped Diagnostic Context):
- Sissetuleva päringu request ID lisada MDC-sse `Before` handler'is
- Logiformaat: `%d [%X{requestId}] %-5level %logger - %msg%n`

Tulemus:
```
[abc-123] INFO  RequestLogger - <eu-authority-1> GET /v1/dataset/uuid - 200 1250ms
[abc-123] INFO  EftiService - getDataset routing: remote gate gate-fi1
[abc-123] INFO  GateClient - gate-fi1 (eDelivery) sendAndReceive - 200 1200ms
[abc-123] INFO  EDeliveryClient - send to gate-fi1 https://... - 200 89ms
```

### 10.3 Äriloogika voogude logimine (prioriteet: KESKMINE)

`EftiService` on keskne äriloogika klass. Lisada tuleks:
- `getDataset()` — logida routing otsus (lokaalne platform vs remote gate) ja tulemus
- `getIdentifiers()` — logida broadcast algus (mitmele gate'ile), lokaalse otsingu tulemuste arv ja koondtulemus
- `saveIdentifiers()` — logida salvestatud identifikaatorite arv ja UIL
- `sendFollowUp()` — logida follow-up suunamine (lokaalne vs remote)
- `handleUilQuery()` / `handleIdentifierQuery()` — logida vastuse genereerimine (tulemuste arv)

### 10.4 Struktureeritud logimine (prioriteet: KESKMINE)

Lisada `logback-classic` + `logstash-logback-encoder`. JSON formaat ainult tootmiskeskkonnas (env muutujaga lülitatav):

```xml
<!-- logback.xml (prod) -->
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <includeMdcKeyName>requestId</includeMdcKeyName>
    <includeMdcKeyName>client</includeMdcKeyName>
</encoder>
```

### 10.5 Auditi logimine (prioriteet: KESKMINE)

Tootmiskeskkonnas on vajalik logida:
- Edukad sisselogimised (kes, millal, milliselt IP-lt, milline roll)
- Administraatori tegevused (kasutaja loomine, gate'i/platvormi/asutuse lisamine/muutmine/kustutamine)
- Andmetele ligipääs (kes küsis millise identifier'i / dataset'i kohta)

See on oluline GDPR ja auditi nõuete täitmiseks.

### 10.6 Autoriseerimise keeldumiste logimine (prioriteet: KESKMINE)

`AccessChecker` ja `User.checkWriteAccess()` peavad logima keeldumised enne `ForbiddenException` viskamist:
```kotlin
log.warn("Access denied for user ${user?.id} to ${exchange.method} ${exchange.path}: insufficient roles")
```

---

## 11. Ettepanekute koondkokkuvõte

| # | Ettepanek | Prioriteet |
|---|-----------|------------|
| 1 | Väljaminevate päringute logimine (GateClient, EDeliveryClient) | KÕRGE |
| 2 | Request ID propageerimine (MDC) | KÕRGE |
| 3 | Äriloogika voogude logimine (EftiService) | KESKMINE |
| 4 | Struktureeritud logimine (JSON, logback) | KESKMINE |
| 5 | Auditi logimine (edukad sisselogimised, admin tegevused, andmetele ligipääs) | KESKMINE |
| 6 | Autoriseerimise keeldumiste logimine | KESKMINE |
| 7 | Ühtne veavormimng (JSON + veakood + request ID) | KESKMINE |
