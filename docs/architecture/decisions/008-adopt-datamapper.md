# ADR-008: DataMapperi kasutuselevõtt vastuse-kujundamiseks

**MUSTAND (otsustajad täpsustamata, 03.09.2026).**

Tihedalt seotud [ADR-007](007-xroad-transport-means-response-shape.md)-ga — ADR-007 variant
"D" eeldab seda otsust, ja käesolev ADR sai ajendi ADR-007 vajadusest. Kui üks lükatakse
edasi, tuleb teine üle vaadata.

## Otsus

Võtame kasutusele **Ruuteri/Bürokrati DataMapperi** (Node.js teenus, Handlebars-mallid
`DSL/DMapper/`-is) kui koha, kus Ruuter kujundab **JSON-vastuse API-lepingu kujusse**. Ruuter
kutsub seda `http.post`-iga nagu iga muu teenuse (uus `DMAPPER_URL` konstant, teenus
`compose.yml`-is).

**Tööjaotus:**

| Teenus | Vastutus |
|---|---|
| **ReSql** | andmepäring — loeb read, ei kujunda API-lepingut |
| **`xml-mapper` (Kotlin)** | XML ↔ JSON eFTI XSD vastu — skeemivalideeriv, keerukas domeeniloogika (subsetid, DG, transpordirežiimid) |
| **DataMapper (uus)** | JSON → JSON — vastuse kuju, väljade nimetamine, massiivide projektsioon, veakeha normaliseerimine |

## Põhjus

### 1. ADR-007 ajend — otsene põhjus

`transport-means` `scope: allgates` vajab **kohalike ja kaug-tulemuste ühtset kuju**. Ilma
DataMapperita on ADR-007 valik kehv:
- **variant A** — uus Kotlin-endpoint `xml-mapper`-isse ainult selle projektsiooni jaoks; või
- **variant B** — loobu "üks endpoint, üks kuju" lubadusest.

DataMapperiga tekib **variant D**: üks Handlebars-mall, mida jooksutatakse **nii kohalike ReSql
ridade kui kaug-otsingu tulemuste peal** → tõesti üks projektsioon, üks kuju, null Kotlin-tööd.
Sama muster kordub iga tulevase `allgates`-tüüpi marsruudi puhul.

### 2. Vastuse-kujundamine on praegu laiali ja implitsiitne

- `get_consignments_by_vehicle.sql` ehitab `jsonb_build_object`-iga käsitsi kureeritud
  projektsiooni — SQL teeb korraga andmepäringut *ja* API-lepingut.
- `get_consignments.sql` tagastab `SELECT *`-kujuga toored read — teine kuju sama domeeni peal.
- Kaug-otsingu tulemused tulevad `xml-mapper` `search/response-to-json`-ist — kolmas kuju.

Kolm kuju ühe mõiste (saadetise identifikaatoriandmed) peale. DataMapper annab **ühe koha**,
kus kuju defineeritakse — loetav, andmebaasita testitav, versioonitav mall.

### 3. Ruuteri DSL-i JS-piirangud lakkavad blokeerimast

Ruuteri avaldusmootoris ei ole tõestatud tuge `.map()` / `Array.isArray` / noolefunktsioonide
jaoks — **kogu PR #121 on ehitatud selle piirangu ümber** (dokumenteeritud igas selle failis).
Massiivi elementhaaval ümberkujundamine DSL-is ei ole variant. DataMapper (Handlebars +
`DSL/DMapper/lib/` JS-helperid) on täpselt selleks mõeldud — piirang kaob response-shaping'ult,
DSL jääb õhukeseks marsruutimis- ja valveloogikaks.

### 4. Referentspinu vastavus

`docs/planning/rest-api-disainijuhend.md` §2.5 andmevoog **juba eeldab DataMapperit**
(`R->>M: map_user { users }` → `M-->>R: { mapped user object }`). Praegune puudumine on
kõrvalekalle disainijuhendist, mitte teadlik lihtsustus. Kasutuselevõtt viib koodi juhendiga
kooskõlla.

### 5. Korduvkasutus üle värava

- audit-logi kirjete koostamine (kui [ADR-006](006-xroad-identity-and-subsets.md) auditi-lugu
  realiseerub) — sündmuse keha kokkupanek ridadest
- veakeha normaliseerimine ühtsesse RFC 7807 kujusse
- `follow_up_log` payload'i kokkupanek
- admin-UI list-vastuste kujundamine (disainijuhendi `map_user`)

## Kaalutud alternatiivid

- **Laienda `xml-mapper`-it (Kotlin).** Töötab, aga iga uus vastuse-kuju = Kotlin-muudatus +
  image build + deploy. `xml-mapper` otstarve on XSD-vastane XML-töö keeruka domeeniloogikaga,
  mitte suvaline JSON→JSON kujundamine — kahe asja segamine paisutab teenuse vastutust.
- **Jää SQL-i juurde (`jsonb_build_object`).** Skaleerub halvasti pesastatud kujude ja
  massiiviteisenduste peale (nt `allgates` liidab kaks eri allikat), ja seob andmepäringu
  API-lepinguga — SQL-i muutus API kuju pärast on vale kihi muutus.
- **Ruuteri `template:` samm.** Ruuteril on `template`-samm, aga see käivitab teise DSL-käsitleja
  alamrutiinina (ja möödub guardist — `rest-api-disainijuhend.md` §-d 208–209), see ei ole
  struktuurne JSON-teisendusmootor.
- **Ära tee midagi (variant B).** ADR-007 lahendatakse lubadusest loobumisega. Odav täna,
  aga iga järgmine marsruut, mis tahab kureeritud vastust üle mitme allika, põrkab samasse
  seina.

## Tagajärjed

**Positiivsed:**
- ADR-007 laheneb variant D-ga — üks kuju, deklaratiivne mall
- selge kihijaotus: ReSql = andmed, `xml-mapper` = XSD-XML, DataMapper = JSON-kuju
- DSL-i JS-piirangud ei ole enam response-shaping'u blokeerija

**Hind:**
- **uus teenus `compose.yml`-is** (Node.js) — juurutusühik, seire, health-probe, versioon
- **`DSL/DMapper/` kataloogistruktuur** — `hbs/` mallid, `lib/` helperid, mallide test-harness
- **õppimiskõver** — Handlebars konventsioonid, DataMapperi kutsemuster Ruuterist
- **migratsioon on järkjärguline** — olemasolevad SQL-projektsioonid võib jätta paigale; uued
  ja `allgates`-tee lähevad DataMapperisse. Täielik üleviimine on eraldi töö, mitte selle ADR-i
  skoop.
- `pikker-deploy.sh` / `compose.pikker.yml` vajavad uue teenuse lisamist

## Rakendatav changeset

1. `compose.yml` + `compose.pikker.yml`: `datamapper` teenus
2. `constants.ini`: `DMAPPER_URL=http://datamapper:3000` (vms)
3. `DSL/DMapper/hbs/`, `DSL/DMapper/lib/` — algstruktuur + esimene mall (`transport_means`
   projektsioon ADR-007 jaoks)
4. `docs/architecture/` — kihijaotus dokumenteeritud
5. ADR-007: MUSTAND → OTSUS variant D-na, kui see ADR ratifitseeritakse

Vt ka [issue #125](https://github.com/kemit-ee/efti-gate-ee/issues/125).
