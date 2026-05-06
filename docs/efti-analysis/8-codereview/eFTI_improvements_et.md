# Parandusettepanekud

| | |
|---|---|
| **Autor** | Sten Viljus |
| **Ettevõte** | Askend Estonia OÜ |
| **Kontakt** | sten.viljus@askend.com |

Koondatud kõigist analüüsidokumentidest: [Koodianalüüs](eFTI_codereview_et.md), [Skaleeritavuse analüüs](eFTI_scalability_et.md), [Paigaldamise juhend](eFTI_deployment_et.md), [Vigade ja logimise spetsifikatsioon](../5-errors-n-logging/eFTI_errors_logging_et.md), [Õiguste ja ligipääsuhalduse dokument](../4-rights-n-permissions/eFTI_rights_and_permissions_et.md).

---

## 1. Turvalisus

| # | Ettepanek | Prioriteet | Maht | Allikas |
|---|-----------|------------|------|---------|
| 1.1 | **TARA autentimine** — Admin UI Basic Auth asendada TARA (riiklik autentimisteenus) autentimisega | KÕRGE | ~5-8 päeva | koodianalüüs 5. ptk |
| 1.2 | **Kasutajanimega sisselogimise keelamine** — tootmises lubada ainult TARA, keelata Basic Auth parooliga | KÕRGE | ~1 päev (koos 1.1-ga) | koodianalüüs 5. ptk |
| 1.3 | **Fast adapter turvalisus** — `X-API-Key` asendada korraliku autentimisega (mTLS või allkirjastatud tokenid) | KÕRGE | ~3-5 päeva | koodianalüüs 5. ptk |
| 1.4 | **Saladuste haldus** — .env failidest turvalisse hoidlasse (Kubernetes Secrets / AWS Secrets Manager) | KÕRGE | ~2-3 päeva | koodianalüüs 5. ptk, skaleeritavuse analüüs |
| 1.5 | **Bearer Auth standardiseerimine** — praegune `base64(id:password)` formaat asendada JWT tokenite või opaque API key'dega. Mittestandardne formaat võib tekitada probleeme kolmandate osapoolte integratsioonidel | KÕRGE | ~3-5 päeva | koodianalüüs |
| 1.6 | **Sertifikaadid välja image'ist** — laadida runtime'il (mounted volumes, Secrets Manager), mitte ehitada image'i sisse | KESKMINE | ~1-2 päeva | koodianalüüs 8. ptk |
| 1.7 | **Rate limiting** — implementeerida reverse proxy / ingress tasemel (Caddy `rate_limit`, nginx-ingress annotatsioonid) | KESKMINE | ~0.5 päeva | koodianalüüs 5. ptk |
| 1.8 | **Ühtne veavorming** — REST API vead tulevad praegu plain text'ina, puudub request ID ja veakood vastuses. Standardne JSON veavorming (`{status, error, message, requestId, timestamp}`) lihtsustab klientpoolset veakäsitlust. Lisaks: XML API-s tuleb otsustada plain text vs XML veaformaat (TODO koodis: `EftiService.checkGateAvailable()`) | KESKMINE | ~1-2 päeva | vigade ja logimise spets., TODO koodis |
| 1.9 | **Auditi logimine** — logida edukad sisselogimised, admin tegevused, andmetele ligipääs (GDPR) | MADAL | ~1 päev | logimise analüüs |
| 1.10 | **checkWriteAccess rolli tüübikontroll** — `User.checkWriteAccess()` kontrollib ainult Party ID olemasolu `roles.values.flatten()`-s, aga ei kontrolli rolli tüüpi. Teoreetiliselt saab GATE rolliga kasutaja kirjutada Platform ressursile, kui Party ID juhuslikult kattub. Koodis on TODO märkus | KÕRGE | ~0.5 päeva | õiguste ja ligipääsuhalduse dok. |
| 1.11 | **Bearer token'i aegumine** — praegusel Bearer Auth'il puudub token'i aegumine ja revoke mehhanism. Kompromiteeritud token kehtib igavesti kuni parooli vahetatakse | KÕRGE | ~1-2 päeva | õiguste ja ligipääsuhalduse dok. |

---

## 2. Logimine ja jälgitavus

| # | Ettepanek | Prioriteet | Maht | Allikas |
|---|-----------|------------|------|---------|
| 2.1 | **GateClient väljaminevate päringute logimine** — logida gate ID, protokoll (Fast/eDelivery), sihtpunkt, tulemus, kestus. Meetodid: `sendAndReceive()`, `getIdentifiers()`, `getDataset()`, `postFollowUp()`, `ping()` | KÕRGE | ~1-2 päeva | logimise analüüs |
| 2.2 | **EDeliveryClient väljaminevate päringute logimine** — logida sihtpunkt, saaja Party ID, request ID, vastuse staatuskood, kestus. Meetodid: `send()`, `sendAndReceive()`, `ping()` | KÕRGE | ~1 päev | logimise analüüs |
| 2.3 | **Request ID propageerimine** — SLF4J MDC mehhanism, et kõik logisõnumid oleks korreleeritavad ühe päringu piires | KÕRGE | ~1-2 päeva | logimise analüüs |
| 2.4 | **EftiService äriloogika logimine** — logida routing otsused (lokaalne vs remote), broadcast algus/tulemus, salvestatud identifikaatorite arv, follow-up suunamine | KESKMINE | ~1-2 päeva | logimise analüüs |
| 2.5 | **Struktureeritud logimine (JSON + ECS)** — lisada `logback-classic` + `logstash-logback-encoder`, JSON formaat ECS standardis tootmiskeskkonnas (env muutujaga lülitatav). Kohustuslikud väljad: `@timestamp`, `log.level`, `trace.id` (requestId), `service.name`, `user.id`, `user.roles`, `url.path`, `client.ip`, `http.response.status_code`, `event.duration`. Vt [Vigade ja logimise spetsifikatsioon](../5-errors-n-logging/eFTI_errors_logging_et.md) ptk 4.1b | KÕRGE | ~2-3 päeva | logimise analüüs, KeMIT MFN |
| 2.6 | **PlatformClient eDelivery variandi logimine** — praegu logib ainult REST varianti, eDelivery delegeerib ilma logimiseta | KESKMINE | ~0.5 päeva | logimise analüüs |
| 2.7 | **Autoriseerimise keeldumiste logimine** — `AccessChecker` ja `User.checkWriteAccess()` peavad logima keeldumised enne `ForbiddenException` viskamist. Praegu keeldumised ei ole logides nähtavad — turvaintsidendid jäävad märkamatuks | KESKMINE | ~0.5 päeva | vigade ja logimise spets. |

---

## 3. Skaleeritavus

Siia on koondatud ainult **tarkvaralised (koodi)muudatused**. Infrastruktuuri ja platvormimuudatused (AWS ECS/RDS/ElastiCache, Kubernetes operaatorid, load balancer'id, CDN jms) on teadlikult välja jäetud — need sõltuvad valitud platvormi variandist ja on detailselt kirjeldatud [Skaleeritavuse analüüsis](eFTI_scalability_et.md).

| # | Ettepanek | Prioriteet | Maht | Allikas |
|---|-----------|------------|------|---------|
| 3.1 | **Registry sünkroonimine** — PostgreSQL `LISTEN/NOTIFY` mehhanism, et in-memory registry muudatused jõuaksid kõigi node'ideni. Praegu `save()` ja `delete()` uuendavad ainult lokaalset `ConcurrentHashMap`'i | KRIITILINE | ~3-5 päeva | skaleeritavuse analüüs etapp 1.1, koodianalüüs 6. ptk |
| 3.2 | **Request ID cache Redis'esse** — duplikaatide kontroll jagatud cache'is, mitte node'i-siseses `Cache`'is | KRIITILINE | ~2-3 päeva | skaleeritavuse analüüs etapp 1.2 |
| 3.3 | **Admin auth state jagamine** — IP-põhine olek Redis'esse või DB-sse | KRIITILINE | ~1-2 päeva | skaleeritavuse analüüs etapp 1.3 |
| 3.4 | **Leader election tausttöödele** — `GatePingJob` ja `IdentifierExpirationJob` ainult ühel node'il | KESKMINE | ~2-3 päeva | skaleeritavuse analüüs etapp 2.1 |
| 3.5 | **Migratsiooni lukk** — DB migratsioonide race condition mitme node'iga käivitumisel | KESKMINE | ~1 päev | skaleeritavuse analüüs etapp 2.2 |
| 3.6 | **Saladuste haldus (koodipool)** — abstraktsioonikiht, et toetada nii env vars, Secrets Manager kui K8s Secrets | KÕRGE | ~2-3 päeva | skaleeritavuse analüüs etapp 3.1 |
| 3.7 | **Sertifikaadid (koodipool)** — failisüsteemi asemel laadida mitmest allikast (K8s Secret mount, Secrets Manager) | KÕRGE | ~2-3 päeva | skaleeritavuse analüüs etapp 3.2 |
| 3.8 | **Health check'id** — laiendada `/health` endpoint'i (DB ühendus, sertifikaatide kehtivus, vaba mälu) | KESKMINE | ~1-2 päeva | skaleeritavuse analüüs etapp 4.3 |

Detailne plaan (sh AWS ja Kubernetes infrastruktuuri variandid) vt [Skaleeritavuse analüüs](eFTI_scalability_et.md).

---

## 4. Liidesed ja integratsioonid

| # | Ettepanek | Prioriteet | Maht | Allikas |
|---|-----------|------------|------|---------|
| 4.1 | **X-tee liidesed** — implementeerida X-tee liidesed asutuste ja platvormidega suhtlemiseks (Eesti riiklik andmevahetuskiht) | KÕRGE | ~10-15 päeva | koodianalüüs 5. ptk |

---

## 5. CI/CD ja paigaldus

| # | Ettepanek | Prioriteet | Maht | Allikas |
|---|-----------|------------|------|---------|
| 5.1 | **Container Registry** — kasutada ghcr.io või AWS ECR. Image'd tagida Git commit hash'iga | KÕRGE | ~1-2 päeva | koodianalüüs 8. ptk |
| 5.2 | **Automaatne deploy** — GitHub Actions workflow: test → build → push → deploy | KÕRGE | ~2-3 päeva | koodianalüüs 8. ptk |
| 5.3 | **Rollback mehhanism** — eelmise versiooni taastamine (tagide kaudu registrist) | KESKMINE | ~1 päev | koodianalüüs 8. ptk |
| 5.4 | **Staging keskkond** — eraldi VPS/namespace samade compose failidega | KESKMINE | ~1-2 päeva | koodianalüüs 8. ptk |
| 5.5 | **Zero-downtime deploy** — Docker Compose'iga raske, Kubernetes'es natiivne (rolling update) | KESKMINE | ~1-2 päeva | koodianalüüs 8. ptk |
| 5.6 | **Versioonimine** — semantiline versioonimine + changelog | MADAL | ~0.5 päeva | koodianalüüs 8. ptk |

---

## 6. Jõudlus ja koodikvaliteet

| # | Ettepanek | Prioriteet | Maht | Allikas |
|---|-----------|------------|------|---------|
| 6.1 | **XSD versioonimine** — formaalne versioonistrateegia XSD failidele, et tagada sujuv üleminek eFTI common dataset model'i uuendustel | KÕRGE | ~1-2 päeva | koodianalüüs |
| 6.2 | **DOM → StAX** — eDelivery sõnumite parsimine ilma kogu dokumendi mällu laadimiseta | KESKMINE | ~2-3 päeva | koodianalüüs 6. ptk |
| 6.3 | **JAXB optimiseerimine** — Unmarshaller pool'imine või StAX-põhine parsimine kõrge koormuse jaoks | KESKMINE | ~1-2 päeva | koodianalüüs 6. ptk |
| 6.4 | **Regex caching** — `Regex(...)` `handleSaveIdentifiersRequest`'is liigutada companion object väljale | MADAL | ~0.5h | koodianalüüs 6. ptk |
| 6.5 | **Expiration SQL** — `IdentifierExpirationJob` filtreerimine DB-s, mitte Kotlin koodis | MADAL | ~0.5 päeva | koodianalüüs 6. ptk |
| 6.6 | **StringBuilder XML'is** — suure tulemuste hulga XML ehitamisel kasutada StringBuilder'it | MADAL | ~0.5 päeva | koodianalüüs 6. ptk |
| 6.7 | **XML kanoniseerimine (C14N)** — `Xml.kt` regex-põhine `canonicalXml` on tegelikult whitespace normaliseerija oma string template'ide jaoks, mitte standardne C14N. Allkirjastamisel kasutatakse seda ainult SOAP ümbriku template'i puhastamiseks — digest'id arvutatakse konkreetsete blokkide pealt eraldi. Standardne C14N oleks formaalselt korrektsem, aga praktiline risk on madal | MADAL | ~2-3 päeva | koodianalüüs |
| 6.8 | **XSD valideerimine CI-s** — automaatne XML näidisfailide valideerimine XSD skeemide vastu CI pipeline'is | MADAL | ~0.5 päeva | koodianalüüs |
| 6.9 | **eDelivery koodi dokumenteerimine** — inline dokumentatsioon kohandatud eDelivery implementatsioonile, et hõlbustada tulevast hooldust | MADAL | ~1-2 päeva | koodianalüüs |
| 6.10 | **Identifier'ite cache** — Caffeine vms caching layer sageli päritud identifier'itele, et vähendada DB koormust | MADAL | ~1-2 päeva | koodianalüüs |
| 6.11 | **Tundmatu rootTag veakäsitlus** — `GateMessageHandler` ja `PlatformMessageHandler` ignoreerivad vaikselt tundmatuid sõnumitüüpe. Tuleb tagastada veateade saatjale (TODO koodis mõlemas failis) | KESKMINE | ~0.5-1 päev | TODO koodis |
| 6.12 | **eDelivery CompressionType kontroll** — `EDeliveryRoutes.decryptPayload()` eeldab alati GZIP-pakkimist, aga peaks kontrollima sõnumi CompressionType välja (TODO koodis) | MADAL | ~0.5 päeva | TODO koodis |
| 6.13 | **Multi-platform kasutajate tugi** — `PlatformRoutes` ei luba kasutajal, kellel on mitu Platform rolli, identifikaatoreid saata. Tuleb võimaldada platformId määramine päringu parameetrina (TODO koodis) | KESKMINE | ~1-2 päeva | TODO koodis |

---

## 7. Testimine

| # | Ettepanek | Prioriteet | Maht | Allikas |
|---|-----------|------------|------|---------|
| 7.1 | **EftiService unit testid** — paralleelne broadcast, local vs remote routing, veakäsitlus | KÕRGE | ~2-3 päeva | koodianalüüs 10. ptk |
| 7.2 | **PlatformClient unit testid** — eDelivery vs REST valik, subsetting, timeout käsitlus | KESKMINE | ~1-2 päeva | koodianalüüs 10. ptk |
| 7.3 | **E2E gate-to-gate test** — kahe instansi vaheline suhtlus (praegu käivitatakse, aga ei testita) | KESKMINE | ~2-3 päeva | koodianalüüs 10. ptk |
| 7.4 | **Veakäsitluse testid** — timeout'id, DB ühenduse kaotus, vigane XML | KESKMINE | ~1-2 päeva | koodianalüüs 10. ptk |
| 7.5 | **Follow-up testid** — follow-up äriloogika testide laiendamine | MADAL | ~1 päev | koodianalüüs 10. ptk |

---

## 9. Frontend (UI)

| # | Ettepanek | Prioriteet | Maht | Allikas |
|---|-----------|------------|------|--------|
| 9.1 | **UI valideerimise parendamine** — `UserForm.svelte` ja `PlatformForm.svelte` reaalajas valideerimine enne vormi saatmist | KESKMINE | ~1-2 päeva | koodianalüüs |
| 9.2 | **Svelte 5 migratsioon** — planeerimine Runes API-le (jõudluse ja arendajakogemuse parendus) | MADAL | ~3-5 päeva | koodianalüüs |

---

## 8. Monitooring

| # | Ettepanek | Prioriteet | Maht | Allikas |
|---|-----------|------------|------|---------|
| 8.1 | **Tsentraalne logimine** — logikogumissüsteem (CloudWatch, Loki + Grafana, ELK) | KESKMINE | ~2-4 päeva | skaleeritavuse analüüs |
| 8.2 | **Meetrikad ja dashboardid** — Prometheus + Grafana või CloudWatch (CPU, mälu, DB ühendused, eDelivery sõnumid) | KESKMINE | ~3-4 päeva | skaleeritavuse analüüs |
| 8.3 | **Alerting** — teavitused kriitiliste sündmuste korral (gate offline, DB ühenduse kaotus, kõrge vigade määr) | MADAL | ~1-2 päeva | skaleeritavuse analüüs |

---

## Koondkokkuvõte

### Prioriteetide järgi

| Prioriteet | Arv | Hinnanguline maht |
|------------|-----|-------------------|
| **KRIITILINE** | 3 | ~6-10 päeva |
| **KÕRGE** | 17 | ~32-51 päeva |
| **KESKMINE** | 23 | ~21-36 päeva |
| **MADAL** | 13 | ~12-19 päeva |
| **Kokku** | **56** | **~71-116 päeva** |

### Teemade järgi

| Teema | Arv | Hinnanguline maht |
|-------|-----|-------------------|
| Turvalisus | 11 | ~19-30 päeva |
| Logimine | 7 | ~6-10 päeva |
| Skaleeritavus | 8 | ~15-22 päeva |
| Liidesed | 1 | ~10-15 päeva |
| CI/CD | 6 | ~6-10 päeva |
| Jõudlus ja koodikvaliteet | 13 | ~12-20 päeva |
| Testimine | 5 | ~7-11 päeva |
| Monitooring | 3 | ~6-10 päeva |
| Frontend | 2 | ~4-7 päeva |

### Soovituslik järjestus

**Esimene faas (tootmisvalmidus):**
1. TARA autentimine + kasutajanimega sisselogimise keelamine (1.1, 1.2)
2. checkWriteAccess tüübikontroll (1.10)
3. Saladuste haldus (1.4, 1.5)
4. Bearer token'i aegumine (1.11)
5. X-tee liidesed (4.1)
6. Rate limiting (1.6)
7. Fast adapter turvalisus (1.3)
8. Registry sünkroonimine (3.1) — kui mitu instansi on plaanis

**Teine faas (kvaliteet ja jälgitavus):**
9. Väljaminevate päringute logimine (2.1, 2.2, 2.6)
10. Request ID propageerimine (2.3)
11. EftiService testid (7.1)
12. Container Registry + automaatne deploy (5.1, 5.2)

**Kolmas faas (skaleerimine ja monitooring):**
13. Request ID cache + admin auth state (3.2, 3.3)
14. Struktureeritud logimine (2.5)
15. Tsentraalne logimine ja meetrikad (8.1, 8.2)
16. Ülejäänud skaleeritavuse muudatused (3.4–3.8)

---

## 10. KeMIT MFN vastavus

Ettepanekud, mis tulenevad KeMIT mittefunktsionaalsete nõuete (versioon 2026 v1.2.0) analüüsist. Analüüs vt `eFTI_codereview_et.md` peatükk 12.

### 10.1 API ja dokumentatsioon

| # | Ettepanek | Prioriteet | Maht | MFN nõue |
|---|-----------|------------|------|----------|
| 10.1.1 | **OpenAPI 3.0+ spetsifikatsioon** — genereerida OpenAPI spec fail kõigile REST endpoint'idele. Lisada Swagger UI või Redoc automaatne dokumentatsioon | KÕRGE | ~2-3 päeva | API: OpenAPI 3.0+, automaatne dok. |
| 10.1.2 | **API versioonimine** — lisada URL-i prefiks `/api/v1/`, defineerida versiooni aegumispoliitika (min 6 kuud vana versiooni toetus) | KÕRGE | ~1-2 päeva | API: versioonimine URL-is |
| 10.1.3 | **RFC 7807 Problem Details veavorming** — asendada plain text veateated standardse JSON struktuuriga `{type, title, status, detail, instance}` | KÕRGE | ~1-2 päeva | API: RFC 7807 veateated |
| 10.1.4 | **CORS poliitika** — konfigureerida eksplitsiitne CORS poliitika (allowed origins, methods, headers) | KESKMINE | ~0.5 päeva | API: CORS |
| 10.1.5 | **Pagination** — lisada lehekülgjaotus identifier'ite otsingusse (RFC 5988 Link päis, offset/limit, metainfo) | KESKMINE | ~1-2 päeva | API: pagination, RFC 5988 |

### 10.2 Autentimine ja seansihaldus

| # | Ettepanek | Prioriteet | Maht | MFN nõue |
|---|-----------|------------|------|----------|
| 10.2.1 | **JWT autentimine (RFC 7519, RFC 9068)** — implementeerida JWT-põhine autentimine koos TARA-ga. Access token + refresh token, token'i aegumisaeg, allkirjastamine | KÕRGE | ~5-8 päeva | Turv.: JWT + TARA, K8s: sessioonihaldus |
| 10.2.2 | **Sessiooni aegumise mehhanism** — konfigureeritav seansi kestvus, automaatne aegumine, kasutaja teavitamine enne aegumist | KÕRGE | ~1-2 päeva | Turv.: seansi aegumine |
| 10.2.3 | **Väljalogimine** — implementeerida turvaline väljalogimine (token'i tühistamine, seansi lõpetamine, ühe klõpsuga) | KÕRGE | ~1 päev | Turv.: väljalogimine |
| 10.2.4 | **Ebaõnnestunud sisselogimiste piiramine** — konfigureeritav arv ja ajavahemik, lukustamine, teavitamine | KESKMINE | ~1 päev | Turv.: rate limiting |
| 10.2.5 | **OAuth2 rakenduste vahel** — rakenduste omavaheline autentimine OAuth2 client credentials flow'ga | KESKMINE | ~2-3 päeva | Konf.: OAuth2 |

### 10.3 Turvalisus ja vastavus

| # | Ettepanek | Prioriteet | Maht | MFN nõue |
|---|-----------|------------|------|----------|
| 10.3.1 | **SonarQube integratsioon** — lisada SonarQube analüüs CI/CD pipeline'i, tagada 0 kõrget/kriitilist viga | KÕRGE | ~1-2 päeva | Turv.: SonarQube |
| 10.3.2 | **Dependency Track / SBOM** — genereerida SBOM (CycloneDX), liidestada KeMIT Dependency Track teenusega | KÕRGE | ~1-2 päeva | Turv.: Dependency Track |
| 10.3.3 | **Container image skaneerimine** — lisada Trivy või Grype CI/CD pipeline'i, blokeerida MEDIUM+ haavatavused | KÕRGE | ~0.5-1 päev | Kont.: haavatavuste skaneerimine |
| 10.3.4 | **robots.txt** — lisada robots.txt fail, mis keelab otsingurobotite juurdepääsu | MADAL | ~0.5h | Turv.: robots.txt |
| 10.3.5 | **WCAG 2.2 AA lõppviimistlus** — baastase olemas (label-input seosed, focus ring'id, ARIA role'id, semantic HTML). Parandada: icon-only nuppudele `aria-label`, modal'ile `aria-labelledby`, skip navigation link, `.text-muted` värvikontrastsus (gray-400 → gray-500+), `SortableTable`'ile `aria-sort` | KESKMINE | ~1-2 päeva | Üld.: WCAG 2.2 AA |

### 10.4 Logimine ja monitooring

| # | Ettepanek | Prioriteet | Maht | MFN nõue |
|---|-----------|------------|------|----------|
| 10.4.1 | **ECS JSON logiformaat** — implementeerida Elastic Common Schema (ECS) formaadis JSON logimine (logback + logstash-logback-encoder või analoog). Kohustuslikud väljad vt ettepanek 2.5 ja `eFTI_errors_logging_et.md` ptk 4.1b. Kattub ettepanekuga 2.5 — tuleb implementeerida koos | KÕRGE | ~1-2 päeva | Log.: ECS standard |
| 10.4.2 | **Prometheus meetrikad** — lisada meetrikate endpoint (Micrometer või Klite-kohandatud lahendus), eksponeerida JVM, HTTP ja ärimeetrikaid | KÕRGE | ~2-3 päeva | Log.: Prometheus |
| 10.4.3 | **Auditi log** — logida kõik andmete vaatamised, loomised, muutmised ja kustutamised seotuna kasutaja identiteedi ja rolliga. Eraldada auditi log rakenduse tööbaasist | KÕRGE | ~3-5 päeva | Log.: auditi log, isiku ja rolliga seostamine |
| 10.4.4 | **Korduvate veateadete optimeerimine** — eksponentsiaalse tagasilöögi (backoff) loogika korduvatele veateatetele logides | MADAL | ~0.5-1 päev | Log.: eksponentsiaalne logimine |

### 10.5 Versioonimine

| # | Ettepanek | Prioriteet | Maht | MFN nõue |
|---|-----------|------------|------|----------|
| 10.5.1 | **Semantiline versioonimine (SemVer)** — kehtestada MAJOR.MINOR.PATCH versioonimise protsess | KÕRGE | ~0.5 päeva | Vers.: SemVer |
| 10.5.2 | **CHANGELOG.md** — luua CHANGELOG.md vastavalt Keep a Changelog 1.1.0 standardile | KÕRGE | ~0.5 päeva | Vers.: CHANGELOG.md |
| 10.5.3 | **Git tag'id** — tähistada iga väljalase Git tag'iga formaadis vX.Y.Z | KÕRGE | ~0.5h | Vers.: Git tags |

### 10.6 Andmebaas

| # | Ettepanek | Prioriteet | Maht | MFN nõue |
|---|-----------|------------|------|----------|
| 10.6.1 | **Andmebaasi objektide kommentaarid** — lisada COMMENT kõigile tabelitele ja väljadele (ingliskeelsed) Flyway migratsioonidega | KESKMINE | ~1 päev | AB: kommenteeritud tabelid |
| 10.6.2 | **Andmekirjete versioneerimine** — implementeerida audit trail / temporal tables andmemuudatuste jälgimiseks | KESKMINE | ~3-5 päeva | AB: andmekirjete versioneerimine |
| 10.6.3 | **Võõrvõtmete indekseerimine** — kontrollida ja lisada puuduvad indeksid kõigile FK väljadele | MADAL | ~0.5 päeva | AB: FK indekseerimine |

### 10.7 Konteinerid ja Kubernetes

| # | Ettepanek | Prioriteet | Maht | MFN nõue |
|---|-----------|------------|------|----------|
| 10.7.1 | **Kubernetes manifestid** — luua K8s Deployment, Service, HPA, ConfigMap, Secret manifestid | KÕRGE | ~3-5 päeva | K8s: HPA, ConfigMap, Secret, ressursipiirangud |
| 10.7.2 | **Liveness/readiness kontrollid** — eraldada `/health/live` ja `/health/ready` endpoint'id, kontrollida DB ühendust, sertifikaatide kehtivust | KÕRGE | ~1 päev | K8s: elusoleku/valmisoleku kontrollid |
| 10.7.3 | **Graceful shutdown** — implementeerida eksplitsiitne SIGTERM käsitlemine, pooleliolevate päringute lõpetamine (30s timeout) | KESKMINE | ~1 päev | K8s: graatsiline sulgemine |
| 10.7.4 | **Minimalistlik baaskujutis** — vahetada JVM image distroless/Alpine variandi vastu | MADAL | ~1 päev | Kont.: minimalistlik baaskujutis |
| 10.7.5 | **Container image allkirjastamine (Cosign)** — lisada kujutise allkirjastamine CI/CD pipeline'i | MADAL | ~0.5-1 päev | Kont.: kujutise allkirjastamine |

### 10.8 Kasutajaliides

| # | Ettepanek | Prioriteet | Maht | MFN nõue |
|---|-----------|------------|------|----------|
| 10.8.1 | **TEDI disainisüsteemi komponentide kasutuselevõtt** — asendada oma Svelte komponendid TEDI komponentidega (https://tedi.tehik.ee/) | KÕRGE | ~5-10 päeva | UI: TEDI disainisüsteem |
| 10.8.2 | **Eestikeelne kasutajaliides** — tõlkida kogu UI eesti keelde (menüüd, vormid, veateated, abitekstid). Implementeerida i18n tugi | KÕRGE | ~3-5 päeva | UI: eestikeelne |
| 10.8.3 | **Rollivalik** — kui kasutajal on mitu rolli, kuvada rollivalik sisselogimisel | KESKMINE | ~1-2 päeva | UI: rollivalik |
| 10.8.4 | **Vormi oleku salvestamine** — perioodiline draft salvestamine, et kasutaja saaks tegevust jätkata | MADAL | ~2-3 päeva | UI: tegevuse jätkamine |

### 10.9 Lähtekood ja koodihoidla

| # | Ettepanek | Prioriteet | Maht | MFN nõue |
|---|-----------|------------|------|----------|
| 10.9.1 | ~~**KeMIT koodihoidlasse migreerimine**~~ — kood on juba KeMIT kontrolli all olevas GitHub repos. **Vastab** | — | — | LK: KeMIT koodihoidla |
| 10.9.2 | **Demo sertifikaatide eemaldamine** — eemaldada demo sertifikaadid repos'ist, lisada genereerimisjuhised | KESKMINE | ~0.5 päeva | LK: saladused koodist välja |
| 10.9.3 | **Koodidokumentatsiooni parendamine** — lisada KDoc/Javadoc kriitiliste klasside ja meetodite jaoks | MADAL | ~2-3 päeva | LK: kommenteeritud kood |

---

## KeMIT MFN koondkokkuvõte

### Prioriteetide järgi (ainult peatükk 10)

| Prioriteet | Arv | Hinnanguline maht |
|------------|-----|-------------------|
| **KÕRGE** | 19 | ~30-52 päeva |
| **KESKMINE** | 11 | ~15-28 päeva |
| **MADAL** | 7 | ~7-11 päeva |
| **Kokku** | **37** | **~52-91 päeva** |

### Soovituslik järjestus (KeMIT MFN)

**Esimene faas (kohustuslikud turvameetmed):**
1. JWT autentimine + TARA (10.2.1)
2. Sessiooni aegumise mehhanism + väljalogimine (10.2.2, 10.2.3)
3. SonarQube + Dependency Track (10.3.1, 10.3.2)
4. Container image skaneerimine (10.3.3)

**Teine faas (API ja dokumentatsioon):**
5. OpenAPI spetsifikatsioon (10.1.1)
6. API versioonimine (10.1.2)
7. RFC 7807 veavorming (10.1.3)
8. SemVer + CHANGELOG.md + Git tags (10.5.1, 10.5.2, 10.5.3)

**Kolmas faas (logimine ja monitooring):**
9. ECS JSON logiformaat (10.4.1)
10. Prometheus meetrikad (10.4.2)
11. Auditi log (10.4.3)

**Neljas faas (infrastruktuur ja UI):**
12. Kubernetes manifestid + liveness/readiness (10.7.1, 10.7.2)
13. KeMIT koodihoidlasse migreerimine (10.9.1)
14. TEDI disainisüsteem + eestikeelne UI (10.8.1, 10.8.2)

> **NB:** Paljud KeMIT MFN ettepanekud kattuvad osaliselt varasemate peatükkide ettepanekutega (nt 1.1 TARA autentimine, 2.5 struktureeritud logimine, 5.6 versioonimine). Reaalsel planeerimisel tuleb need ühendada, et vältida topelttööd.
