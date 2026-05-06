# eFTI Gate — Õiguste ja ligipääsuhalduse dokument

| | |
|---|---|
| **Autor** | Sten Viljus |
| **Ettevõte** | Askend Estonia OÜ |
| **Kontakt** | sten.viljus@askend.com |

## 1. Ülevaade

eFTI Gate kasutab rollipõhist juurdepääsu kontrolli (RBAC) koos ressursipõhise filtreerimisega. Autentimine toimub HTTP Authorization headeri kaudu (Basic või Bearer), ligipääsu kontroll annotatsioonipõhiselt (`@Access`, `@Public`).

Implementatsioon: `AccessChecker.kt` (Klite `Before` handler).

## 2. Rollide definitsioonid

Rollid on defineeritud enumina `User.kt`:

```kotlin
enum class Role {
  ADMIN, GATE, PLATFORM, AUTHORITY
}
```

### 2.1 ADMIN

| Omadus | Väärtus |
|--------|---------|
| Kirjeldus | Süsteemi administraator |
| Ligipääs | Kõik Admin API ja eFTI API endpointid |
| Filtreerimine | Super Admin näeb kõiki ressursse; tavaline Admin näeb ainult oma rollidega seotud ressursse |
| Piirangud | Consignment'ide kustutamine ainult Super Admin'ile |

**Super Admin** = `isAdmin == true && roles.isEmpty()`. Näeb ja haldab kõiki ressursse ilma piiranguteta.

**Tavaline Admin** = `isAdmin == true && roles.isNotEmpty()`. Näeb ainult oma rollidega seotud ressursse (`listFor()` filtreerimine). Kirjutusõigus ainult oma Party ID-dele (`checkWriteAccess()`).

### 2.2 GATE

| Omadus | Väärtus |
|--------|---------|
| Kirjeldus | Gate'i operaator |
| Ligipääs | Admin API kaudu — ainult Gate'ide haldamine |
| Filtreerimine | Näeb ainult oma Gate'i (`GateRegistry.listFor()`) |
| Piirangud | Kirjutus/kustutamine ainult oma Gate'ile (`checkWriteAccess()`) |

**NB:** Gate roll ei anna otsest ligipääsu eFTI API-le (`/v1/`). Gate-to-gate suhtlus toimub eDelivery kaudu (`/services/`).

### 2.3 PLATFORM

| Omadus | Väärtus |
|--------|---------|
| Kirjeldus | Platformi operaator |
| Ligipääs | Admin API kaudu — platformide haldamine; eFTI API kaudu — identifikaatorite registreerimine |
| Filtreerimine | Näeb ainult oma Platformi (`PlatformRegistry.listFor()`) |
| Piirangud | eFTI API-s peab kasutajal olema täpselt üks Platform roll (mitu platvormi → viga) |

### 2.4 AUTHORITY

| Omadus | Väärtus |
|--------|---------|
| Kirjeldus | Pädeva asutuse (Competent Authority) operaator |
| Ligipääs | Admin API kaudu — asutuste haldamine; eFTI API kaudu — andmepäringud ja follow-up |
| Filtreerimine | Näeb ainult oma Asutust (`AuthorityRegistry.listFor()`) |
| Piirangud | Dataset'i subsetid piiratakse kasutaja subsets väljaga |

## 2b. Ressursside loetelu

| Ressurss | Kirjeldus | Identifikaator | Hoidla |
|----------|-----------|----------------|--------|
| **Gate** | Võrgustiku sõlmpunkt (teine eFTI gate) | `PartyId<Gate>` (tekst, nt `"POC"`) | `GateRegistry` (in-memory + DB) |
| **Platform** | Andmeplatvorm, mis hoiab dataset'e | `PartyId<Platform>` (tekst) | `PlatformRegistry` (in-memory + DB) |
| **Authority** | Pädev asutus (Competent Authority) | `PartyId<Authority>` (tekst) | `AuthorityRegistry` (in-memory + DB) |
| **User** | Süsteemi kasutaja | `UUID` | `UserRepository` (DB) |
| **Consignment** | Kaubaveo andmestik (metaandmed) | `UUID` (datasetId) | `ConsignmentRepository` (DB) |
| **Identifier** | Transpordiidentifikaator | `(id, datasetId)` composite | `ConsignmentRepository` (DB) |
| **Dataset** | Kaubaveo täisandmestik (XML) | UIL: `gateId/platformId/datasetId` | Platvormil (gate ei salvesta) |

## 2c. resource.action õiguste loetelu

| Õigus | Kirjeldus | Kes omab |
|-------|-----------|----------|
| `gate.read` | Gate'ide loetelu ja info vaatamine | ADMIN (filtreeritud) |
| `gate.write` | Gate lisamine ja muutmine | ADMIN + `checkWriteAccess` |
| `gate.delete` | Gate kustutamine | ADMIN + `checkWriteAccess` |
| `gate.ping` | Gate ühenduse testimine | ADMIN + `checkWriteAccess` |
| `platform.read` | Platformide loetelu ja info vaatamine | ADMIN (filtreeritud) |
| `platform.write` | Platform lisamine ja muutmine | ADMIN + `checkWriteAccess` |
| `platform.delete` | Platform kustutamine | ADMIN + `checkWriteAccess` |
| `platform.ping` | Platform ühenduse testimine | ADMIN + `checkWriteAccess` |
| `authority.read` | Asutuste loetelu ja info vaatamine | ADMIN (filtreeritud) |
| `authority.write` | Asutus lisamine ja muutmine | ADMIN + `checkWriteAccess` |
| `authority.delete` | Asutus kustutamine | ADMIN + `checkWriteAccess` |
| `user.read` | Kasutajate loetelu | ADMIN (filtreeritud oma rollide järgi) |
| `user.write` | Kasutaja loomine/muutmine | ADMIN (ainult oma rollide piires) |
| `user.delete` | Kasutaja kustutamine | ADMIN (ei saa iseennast) |
| `consignment.read` | Consignment'ide loetelu | ADMIN (filtreeritud platvormi järgi) |
| `consignment.delete` | Consignment kustutamine | Super Admin |
| `identifier.write` | Identifikaatorite registreerimine | PLATFORM (täpselt 1 platvorm) |
| `identifier.read` | Identifikaatorite otsing (broadcast) | AUTHORITY, ADMIN |
| `dataset.read` | Dataset'i pärimine (UIL alusel) | AUTHORITY, ADMIN |
| `followup.write` | Follow-up sõnumi saatmine | AUTHORITY, ADMIN |

**Märkus:** Need õigused on tuletatud koodist, mitte konfigureeritud süsteemis. Praegu on õigused fikseeritud rollide ja `@Access` annotatsioonide kaudu — eraldi õiguste tabelit andmebaasis ei ole.

## 3. Kasutajamudel

Kasutaja andmestruktuur (`User.kt`):

```kotlin
data class User(
  val name: String,
  val email: Email? = null,
  val subsets: Set<Subset>? = null,
  val isAdmin: Boolean = false,
  val roles: Map<Role, Set<PartyId<*>>> = emptyMap(),
  val id: UUID = randomUUID(),
)
```

| Väli | Tüüp | Kirjeldus |
|------|-------|-----------|
| `id` | UUID | Unikaalne kasutaja ID |
| `name` | String | Kuvatav nimi |
| `email` | Email? | E-post (Basic Auth kasutajanimi) |
| `isAdmin` | Boolean | Admin-lipp |
| `roles` | Map<Role, Set<PartyId>> | Roll → Party ID-de kaardistus |
| `subsets` | Set<Subset>? | Lubatud eFTI subsetid (AUTHORITY kasutajatele) |

### 3.1 Ligipääsu otsuse loogika

```
isSuperAdmin = isAdmin && roles.isEmpty()
```

Ressursile ligipääsu kontroll:
1. `isSuperAdmin` → ligipääs kõigile
2. `isAdmin && roles.isNotEmpty()` → ligipääs ainult oma Party ID-dele
3. `!isAdmin` → ligipääs ainult `@Access` annotatsioonile vastavatele endpointidele, kus kasutajal on vastav roll

Kirjutusõiguse kontroll (`checkWriteAccess()`):
1. `isSuperAdmin` → lubatud
2. Muul juhul → `entityId` peab olema kasutaja `roles.values.flatten()` hulgas

## 4. Autentimisviisid

Implementatsioon: `AccessChecker.before()`.

### 4.1 Basic Auth

```
Authorization: Basic base64(email:parool)
```

- Kasutajanimi: e-posti aadress
- Parool: selgetekstiline, kontrollitakse häshi vastu
- Kasutusel: Admin UI (Svelte) brauseri natiivse autentimisdialoogi kaudu
- Otsing: `UserRepository.byCredentials(Email, Password)`

### 4.2 Bearer Auth

```
Authorization: Bearer base64(userId:parool)
```

- Kasutajanimi: kasutaja UUID
- Parool: selgetekstiline, kontrollitakse häshi vastu
- Kasutusel: API kliendid (platformid, asutused, gate'id)
- Otsing: `UserRepository.byCredentials(UUID, Password)`

**NB:** See ei ole JWT. Token on `base64(userId:password)` — mittestandardne formaat.

### 4.3 X-API-Key (eDelivery)

```
X-API-Key: gateId
```

- Kasutusel: `/services/fast` endpoint (gate-to-gate fast-protokoll)
- AccessChecker ei kontrolli — endpointil puudub `@Access` annotatsioon
- **TODO:** API key turvalisus vajab täiendamist (vt koodis märkust)

### 4.4 Autentimata endpointid

| Endpoint | Annotatsioon | Kirjeldus |
|----------|-------------|-----------|
| `/health` | puudub | Health check |
| `/services/msh` | puudub | eDelivery AS4 MSH (TLS sertifikaadipõhine) |
| `/services/fast` | puudub | Gate-to-gate fast-protokoll (X-API-Key) |
| OpenAPI `/api/openapi`, `/v1/openapi` | `@Public` | API dokumentatsioon |

## 5. Parooli haldus

- Häshimine: `KeyGenerator.hash(password, userId)` → Base64
- Salt: kasutaja UUID
- Salvestus: `secretHash` väli `users` tabelis
- Uus parool: genereeritakse `UserAdminRoutes.save()` kaudu (`generateSecret=true`)
- Bearer tokeni väljastamine: `base64(userId:password)` tagastatakse kasutaja loomisel

## 6. Endpoint ↔ õiguste maatriks

### 6.1 Admin API (`/api`)

AccessChecker on registreeritud `before` handler'ina kogu `/api` kontekstile.

| Endpoint | Method | Annotatsioon | Kes pääseb ligi | Filtreerimine |
|----------|--------|-------------|-----------------|---------------|
| `/api/user` | GET | `@Access(ADMIN)` | Admin | Tagastab praeguse kasutaja |
| `/api/switch` | GET | `@Access(ADMIN)` | Admin | Kasutaja vahetus (Basic Auth realm) |
| `/api/users` | GET | `@Access(ADMIN)` | Admin | Super Admin: kõik; Admin: oma rollide kasutajad |
| `/api/users` | POST | `@Access(ADMIN)` | Admin | Uus kasutaja saab ainult looja rollid (v.a. Super Admin) |
| `/api/users/:userId` | DELETE | `@Access(ADMIN)` | Admin | Ei saa kustutada iseennast |
| `/api/gates` | GET | `@Access(ADMIN)` | Admin | Super Admin: kõik; Admin: oma Gate'id |
| `/api/gates` | POST | `@Access(ADMIN)` | Admin | `checkWriteAccess(gate.id)` |
| `/api/gates/:gateId` | DELETE | `@Access(ADMIN)` | Admin | `checkWriteAccess(gateId)` |
| `/api/gates/:gateId/ping` | POST | `@Access(ADMIN)` | Admin | `checkWriteAccess(gateId)` |
| `/api/gates/own` | GET | `@Access(ADMIN)` | Admin | Oma Gate'i konfiguratsioon |
| `/api/platforms` | GET | `@Access(ADMIN)` | Admin | Super Admin: kõik; Admin: oma Platformid |
| `/api/platforms` | POST | `@Access(ADMIN)` | Admin | `checkWriteAccess(platform.id)` |
| `/api/platforms/:platformId` | DELETE | `@Access(ADMIN)` | Admin | `checkWriteAccess(platformId)` |
| `/api/platforms/:platformId/ping` | POST | `@Access(ADMIN)` | Admin | `checkWriteAccess(platformId)` |
| `/api/authorities` | GET | `@Access(ADMIN)` | Admin | Super Admin: kõik; Admin: oma Asutused |
| `/api/authorities/:authorityId` | GET | `@Access(ADMIN)` | Admin | `checkWriteAccess(authorityId)` |
| `/api/authorities` | POST | `@Access(ADMIN)` | Admin | `checkWriteAccess(authority.id)` |
| `/api/authorities/:authorityId` | DELETE | `@Access(ADMIN)` | Admin | `checkWriteAccess(authorityId)` |
| `/api/consignments` | GET | `@Access(ADMIN)` | Admin | Super Admin: kõik; Admin: oma platformi consignment'id |
| `/api/consignments/:datasetId` | DELETE | `@Access(ADMIN)` | Super Admin | Ainult `isSuperAdmin` |

**Oluline:** Kõik Admin API endpointid on `@Access(ADMIN)`. GATE, PLATFORM, AUTHORITY rollid Admin API-le otse ligi ei pääse. Admin kasutaja, kellel on nt `roles = {GATE: ["POC"]}`, näeb ja haldab ainult seda Gate'i — filtreerimine toimub `listFor()` ja `checkWriteAccess()` kaudu.

### 6.2 eFTI API (`/v1`)

AccessChecker ja RequestIdValidator on registreeritud `before` handler'itena kogu `/v1` kontekstile.

| Endpoint | Method | Annotatsioon | Kes pääseb ligi | Piirangud |
|----------|--------|-------------|-----------------|-----------|
| `/v1/identifiers/:datasetId` | POST | `@Access(PLATFORM)` | Platform | Kasutajal peab olema täpselt 1 Platform roll |
| `/v1/identifiers/:identifier` | GET | `@Access(AUTHORITY, ADMIN)` | Authority, Admin | Identifier otsing, broadcast teistele gate'idele |
| `/v1/dataset/:gateId/:platformId/:datasetId` | GET | `@Access(AUTHORITY, ADMIN)` | Authority, Admin | Dataset päring subsetide kaupa |
| `/v1/follow-up/:gateId/:platformId/:datasetId/:datasetRequestId` | POST | `@Access(AUTHORITY, ADMIN)` | Authority, Admin | Järelteade platformile |

### 6.3 eDelivery (`/services`)

AccessChecker **ei ole** registreeritud `/services` kontekstile. Turve põhineb TLS sertifikaatidel ja X-API-Key headeril.

| Endpoint | Method | Turve | Kirjeldus |
|----------|--------|-------|-----------|
| `/services/msh` | POST | TLS sertifikaat | eDelivery AS4 sõnumivahetus |
| `/services/fast` | POST | `X-API-Key` header | Gate-to-gate fast-protokoll |

### 6.4 Muud

| Endpoint | Method | Turve | Kirjeldus |
|----------|--------|-------|-----------|
| `/health` | GET | puudub | Health check |
| `/` | GET | puudub | Admin UI (Svelte SPA) |
| `/api/js-error` | POST | puudub (`@Hidden`) | Frontend veateated |

## 7. Ressursipõhine filtreerimine

Iga ressursitüübi registris on `listFor(user)` meetod, mis filtreerib tulemusi kasutaja rollide järgi:

| Registri klass | Loogika |
|---------------|---------|
| `GateRegistry.listFor()` | Super Admin: kõik; muul juhul: `roles[GATE].contains(gate.id)` |
| `PlatformRegistry.listFor()` | Super Admin: kõik; muul juhul: `roles[PLATFORM].contains(platform.id)` |
| `AuthorityRegistry.listFor()` | Super Admin: kõik; muul juhul: `roles[AUTHORITY].contains(authority.id)` |
| `ConsignmentRepository.listFor()` | Super Admin: kõik; muul juhul: `platformId` peab kuuluma kasutaja PLATFORM rollidesse |
| `UserRepository.byRoles()` | Super Admin: kõik (filtreeritav); muul juhul: kasutaja enda rollide kasutajad |

## 8. Kasutajate haldamise reeglid

Kasutajate haldamine toimub `UserAdminRoutes` kaudu:

1. **Rollide piiramine:** Admin saab luua kasutajaid ainult oma rollidega (v.a. Super Admin, kes saab määrata suvalisi rolle). Vt `ensureAllowedRoles()`.
2. **Subsettide valideerimine:** Authority kasutaja subsetid peavad olema Authority enese subsettide alamhulk. Vt `checkAuthorityUserSubsets()`.
3. **Kustutamine:** Admin ei saa kustutada iseennast. Kustutada saab ainult kasutajaid, keda `list()` tagastab (st oma rollide piires).

## 9. Request ID duplikaatide kontroll

`RequestIdValidator` (ainult `/v1` kontekstis):

- Kontrollib `X-Request-ID` headeri unikaalsust
- Cache: 600 sekundit (10 minutit)
- Duplikaat → `400 Bad Request`
- Sisemise request ID formaat: `internalUUID/externalRequestId`

## 10. Token'i struktuur

### 10.1 Praegune seis (Bearer token)

Süsteem **ei kasuta JWT-d**. Praegune Bearer token on mittestandardne formaat:

```
Authorization: Bearer base64(userId:password)
```

Token'il **puuduvad**:
- Aegumine (expiry)
- Väljastaja (issuer)
- Allkirjastamine (signature)
- Revoke mehhanism
- Scope/claims

Token kehtib kuni kasutaja parool vahetatakse või kasutaja kustutatakse.

### 10.2 Kavandatav JWT claim'ide struktuur

Kui Bearer Auth standardiseeritakse JWT-le (vt [Parandusettepanekud](../8-codereview/eFTI_improvements_et.md) ettepanek 1.5), peaks token'i struktuur olema:

```json
{
  "header": {
    "alg": "RS256",
    "typ": "JWT",
    "kid": "efti-gate-key-1"
  },
  "payload": {
    "sub": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Platform API User",
    "email": "api@platform.example",
    "iss": "efti-gate-poc",
    "aud": "efti-gate",
    "iat": 1700000000,
    "exp": 1700003600,
    "roles": {
      "PLATFORM": ["platform-ee1"]
    },
    "subsets": null,
    "is_admin": false
  }
}
```

| Claim | Tüüp | Kirjeldus |
|-------|------|-----------|
| `sub` | UUID | Kasutaja unikaalne ID |
| `name` | string | Kuvatav nimi |
| `email` | string? | E-posti aadress |
| `iss` | string | Väljastaja (gate identifikaator) |
| `aud` | string | Sihtkoht |
| `iat` | number | Väljastamise aeg (Unix timestamp) |
| `exp` | number | Aegumise aeg (Unix timestamp) |
| `roles` | object | `Map<Role, Set<PartyId>>` — sama struktuur mis `User.roles` |
| `subsets` | array? | Lubatud eFTI subsetid (AUTHORITY kasutajatele) |
| `is_admin` | boolean | Admin-lipp |

**Soovitused:**
- Allkirjastamine: RS256 (asym meetriline, et teised teenused saaksid valideerida ilma privaatvõtmeta)
- Aegumine: 1h (API tokenid), 8h (Admin UI sessioon)
- Refresh token: eraldi opaque token pikendamiseks
- Revoke: token blacklist Redis'es (või lühike `exp` + refresh token revoke)

---

## 11. Turvaaspektid ja teadaolevad puudused

### Implementeeritud
- Rollipõhine ligipääsu kontroll (`@Access` annotatsioonid)
- Ressursipõhine filtreerimine (`listFor()`, `checkWriteAccess()`)
- Parooli häshimine (salt = userId)
- Request ID duplikaatide kontroll (replay kaitse)
- OPTIONS päringud autentimata (CORS)

### Puudused ja TODO-d
1. **Bearer Auth formaat** — mittestandardne `base64(userId:password)`, pole JWT. Puudub token'i aegumine.
2. **X-API-Key turvalisus** — `/services/fast` endpoint kasutab gate ID-d API key-na, valideerimisloogika puudulik (vt TODO koodis).
3. **checkWriteAccess tüübikontroll** — `roles.values.flatten().contains(entityId)` ei kontrolli rolli tüüpi, ainult Party ID olemasolu (vt TODO koodis: "maybe check for type of entityId").
4. **Sessiooni puudumine** — iga päring autentitakse eraldi (stateless). Admin UI jaoks tähendab see Basic Auth dialoogi igal külastusel.
5. **Rate limiting** — puudub (v.a. Request ID duplikaatide kontroll).
6. **Autoriseerimise logimine** — ForbiddenException viskamisel ei logita keeldumist (ainult catch-harus log.error).
