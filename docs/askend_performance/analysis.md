# Konsignatsioonide otsingu jõudlusanalüüs

Järg Antoni koormustestile (`docs/performance/performance-report.md`, commitid
`c34998a` / `5d4b575` / `e24d747`). Selgitab, mida mõõdeti, mida numbrid
tegelikult peegeldavad, ja mida muuta — nii testides kui koodis.

Uuesti mõõdetud `dev@386c5b5` peal, harul `perf/consignments-search-analysis`.

---

## 1. Mida Anton mõõtis

- **Tööriist:** ApacheBench (`ab`).
- **Endpoint:** `POST /efti/api/v1/authority/search` — Authority
  identifikaatori-/konsignatsioonide otsing. Keha (`docs/performance/search.json`):
  `{"mainTransportId": {"operator": "EQ", "id": "VESSEL-001"}}`.
- **Andmed:** seed, seejärel 1 000 000 rida
  `docs/performance/bulk-insert-consignments.sql`-iga
  (`platform_id='mock'`, `gate_id='EE'`, `main_transport_id='BULK-<i>'`).
- **Võrdlus:** Digilogistika/Pikkeri Java-PoC (`kemit-ee/efti-gate-poc`).

Tema raporteeritud (16-tuumaline masin):

| Andmed | `-n` | `-c` | req/s | p99 (ms) | vs PoC |
|---|---:|---:|---:|---:|---|
| seed | 100 | 10 | 70 | 240 | ~20× aeglasem |
| seed | 1 000 | 100 | 93 | 1 800 | ~120× aeglasem |
| seed | 5 000 | 250 | 93 | 4 300 | ~100× aeglasem |
| 1M | 15 | 10 | 0.6 | 15 000 | — |

---

## 2. Mida need numbrid tegelikult peegeldavad

Üks `authority/search` number segab kokku mitu asja:

1. **`authority/search` ei ole ainult DB-päring.** Kui lokaalne otsing midagi ei
   tagasta, jookseb DSL edasi: `criteria-to-xml` (xml-mapper) →
   `forward_to_gates` (multiplexer, **timeout 65 s**) → `xml-to-json`
   (xml-mapper) → `respond_first`. Üks `ab` number katab neli teenust +
   cross-gate fan-out'i. Allikas: `DSL/Ruuter/efti/POST/api/v1/authority/search.yml`.

2. **1M bulk-andmed ei vasta päringule.** `local_search` filtreerib
   `gate_id = '[#OWN_GATE_ID]'` = `EU-EE`; bulk-read on `gate_id = 'EE'`
   → **ükski bulk-rida ei kvalifitseeru**, ja `VESSEL-001` pole bulk-hulgas.
   Nii et 1M-test mõõdab "skaneeri 1M rida → ära leia midagi → kuku multiplexerisse",
   mitte "leia üks rida miljoni seast".

3. **Ruuter on CPU-piiratud.** `compose.yml`: `ruuter: cpus: '0.5', mem_limit:
   512M` (`compose.override.yml` seda ei tõsta). JS-i tõlgendav DSL-mootor
   pool_e tuumaga → 70–93 req/s ongi ootuspärane. See on **konfi**, mitte koodi number.

4. **ReSql pool on 10.** `resql.yaml`: `max_connections: 10`. `-c 250` juures
   10 ühenduse vastu on suurem osa p99-st järjekord, mitte päring.

5. **`ab -c 250` ilma ramp-up'i / think-time'ita** mõõdab küllastust, mitte latentsust.

---

## 3. Juurpõhjus: `get_consignments` päring

`DSL/Resql/efti/POST/get_consignments.sql`:

```sql
FROM (
  SELECT DISTINCT ON (platform_id, dataset_id) *
  FROM consignments
  ORDER BY platform_id, dataset_id, created_at DESC   -- kogu tabeli sort, iga päring
) latest
WHERE status != 'DELETED' AND (:gateId IS NULL OR gate_id = :gateId)
  AND ( <kriteeriumid, üks AND-plokk välja kohta> )
```

Kolm kuhjuvat probleemi:

1. **Filtrit ei suruta alla.** Alampäringul pole `WHERE`-i, seega PG
   materialiseerib "viimane rida iga `(platform_id, dataset_id)` kohta"
   **terve tabeli ulatuses** enne ühegi kriteeriumi rakendamist. 1M rida →
   1M-realine Sort + Unique igal päringul (spillib kettale vaikimisi `work_mem`-i juures).

2. **Indeksi veerujärjekord ei sobi.** `idx_consignments_dataset_latest` on
   `(dataset_id, platform_id, created_at DESC)`; `DISTINCT ON` /
   `ORDER BY` on `(platform_id, dataset_id, created_at DESC)`. Esiveerg erineb
   → PG ei saa indeksit distinct'i jaoks kasutada, teeb Sort'i.

3. **20+ kriteeriumi-indeksit on surnud.** `idx_consignments_main_transport_id`
   jt saavad aidata ainult baastabeli predikaati; siin jookseb predikaat juba
   materialiseeritud `latest` hulgale.

Append-only "viimane rida võidab" muster teeb **iga lugemise O(tabeli suurus)**,
kui pole hooldatud "is-latest" markerit. Mõõdetud plaan 1M real: jaotis 4c.

---

## 4. Uuesti mõõdetud sellel masinal

### Keskkond

- Docker Desktop, ainult `compose.yml` (mitte `override`), kõik pildid ehitatud
  `dev@386c5b5` pealt. Masin jooksutas paralleelselt ka teist stäkki → **absoluutväärtused
  on Antoni omadest kehvemad; oluline on kuju ja kihtide vahe, mitte absoluutnumber.**
- PostgreSQL 18.4 (aarch64), `work_mem = 4MB`, `shared_buffers = 128MB` (vaikimisi).
- **Jaotised 4a–4g:** `ruuter cpus: '0.5'`, `resql pool 10` (algne seis).
  **Jaotised 4j / 6.6 / 6.7:** `ruuter cpus: '1.0'`, `database cpus: '1.0'`,
  `resql pool 75` (tõstetud — vt 6.6).
- Koormus aetud sidecar-konteinerist compose-võrgus (`http://ruuter:8086` /
  `http://resql:8090`), nii et host-portide konflikti pole.

### 4a. Anton uuesti — `authority/search`, 1 seeditud rida (VESSEL-001)

| `-n` | `-c` | req/s | p50 (ms) | p99 (ms) | märkus |
|---:|---:|---:|---:|---:|---|
| 100 | 10 | 36.8 | 220 | 408 | |
| 1 000 | 100 | 37.7 | 2 475 | 4 503 | throughput lapik |
| 5 000 | 250 | 25.2 | 9 103 | 22 308 | 112 non-2xx — järjekord kollapseerib |

**Läbilaskevõime on lapik ~37 req/s** sõltumata konkurentsusest; `-c` kasv
lihtsalt kasvatab järjekorda. Ruuter 0,5 tuumaga on sein.

### 4b. Isoleeri DB — `ab` otse ReSql-i `POST /efti/get_consignments`, 1 rida

| `-n` | `-c` | req/s | p50 (ms) | p99 (ms) |
|---:|---:|---:|---:|---:|
| 2 000 | 50 | **1 965** | 19 | 68 |
| 2 000 | 100 | 1 548 | 51 | 142 |

**DB+ReSql annab ~2000 req/s samale päringule.** Ruuteri kaudu 37 req/s.
Väikese mahu juures **ei ole DB pudelikael — Ruuteri per-request overhead
(JS-eval, DSL-sammud) 0,5 tuumaga on ~50×**.

### 4c. 1M rida — `EXPLAIN (ANALYZE, BUFFERS)`, praegune `get_consignments.sql`

Päring: `gate_id='EU-EE'`, `main_transport_id='BULK-500000'` (üks rida 1M seast).

```
Limit  (actual time=18610..21922 rows=1)
  ->  Subquery Scan on latest
        Filter: status <> 'DELETED' AND gate_id = 'EU-EE' AND main_transport_id = 'BULK-500000'
        Rows Removed by Filter: 1 000 000            <-- filter viskab 999 999 rida ära PÄRAST sorti
        ->  Unique (rows=1 000 001)
              ->  Gather Merge (Workers: 2)
                    ->  Sort
                          Sort Key: platform_id, dataset_id, created_at DESC
                          Sort Method: external merge  Disk: 84 040 kB  (×3 = ~245 MB kettale)
                          ->  Parallel Seq Scan on consignments (rows=333 334 × 3)   4.1 s
Execution Time: 23 337 ms
```

Iga päring: skaneeri 1M rida → sorti 1M rida (spill ~245 MB kettale) → dedupe →
filtreeri 1 reani. **23 s ühe rea tagastamiseks.**

### 4d. 1M rida — `ab` otse ReSql-i, praegune SQL

| `-n` | `-c` | req/s | p50 (ms) | märkus |
|---:|---:|---:|---:|---|
| 10 | 5 | **0.14** | 30 519 | 9/10 ebaõnnestub (ReSql `request_timeout_seconds: 30`) |

Konkureerivad päringud sordivad igaüks oma 245 MB kettale samaaegselt →
veel hullem kui Antoni 0.6 req/s.

### 4e. Parandus A — ainult sobiv indeks (`platform_id, dataset_id, created_at DESC`)

```
->  Index Scan using idx_consignments_latest      (sort kadus, aga...)
      Buffers: shared read=728 143                (728k lehte kettalt — juhuslik heap I/O)
Execution Time: 26 930 ms                          <-- HULLEM
```

Indeks kaotab sordi, aga index-scan → 1M laia rea heap-fetch on aeglasem kui
seq-scan + sort, kui tabel ei mahu `shared_buffers`-i. **Ainult veerujärjekorra
parandus ei aita.**

### 4f. Parandus B — `is_latest` lipp + päringu ümberkirjutus

```sql
ALTER TABLE consignments ADD COLUMN is_latest boolean NOT NULL DEFAULT true;
-- insertimisel: eelmine (platform_id, dataset_id) rida -> is_latest = false
CREATE UNIQUE INDEX uq_consignments_latest ON consignments (platform_id, dataset_id) WHERE is_latest;

-- get_consignments.sql muutub:
SELECT ... FROM consignments
WHERE is_latest AND status != 'DELETED' AND gate_id = :gateId AND <kriteerium>
```

```
->  Index Scan using idx_consignments_main_transport_id on consignments
      Index Cond: (main_transport_id = 'BULK-500000')
      Filter: is_latest AND status <> 'DELETED' AND gate_id = 'EU-EE'
      Buffers: shared read=4
Execution Time: 1.686 ms                           <-- ~14 000× kiirem
```

Kasutab **olemasolevat** `idx_consignments_main_transport_id`-i, loeb 4 lehte,
1,7 ms. Kõik 20+ kriteeriumi-indeksit lähevad tööle.

### 4g. Bulk-insert'i ajakulu (Antoni lahtine TODO)

1M rida ühe `INSERT ... SELECT generate_series`-iga, 24 indeksit (sh GIN):
**312 s** (~3 200 rida/s). `ANALYZE` järel 18 s. (Antoni "~2 sek per
konsignatsioon" oli HTTP API kaudu ükshaaval; bulk-SQL on 3 200/s.)

### 4j. 3-kihiline mõõt praeguse setupiga — DB → ReSql → Ruuter

Setup: `ruuter cpus 1.0`, `database cpus 1.0`, `resql pool 75`, 1 seeditud rida
(`VESSEL-001`), **Ruuter 0.9.12-rc** (Docker Hub oli 0.9.14-rc tõmbamise ajal maas
— vt 6.8). DB-kiht `pgbench`-iga konteinerisisese unix-socketi kaudu (HTTP-ta),
ReSql ja Ruuter `ab`-iga compose-võrgus.

| kiht | c=1 | c=10 | c=20 | c=50 |
|---|---|---|---|---|
| **DB otse** (`pgbench`, socket, sama `get_consignments` päring) | **3614 tps / 0,28 ms** | 2433 / 4,1 ms | 2327 / 8,6 ms | 1415 / 35 ms |
| **ReSql** (`ab`, `POST /efti/get_consignments`) | **738 rps / 1,4 ms** | 1247 / 8 ms | 1125 / 18 ms | 570 / 88 ms |
| **Ruuter** (`ab`, `POST /efti/api/v1/authority/search`) | **87 rps / 11,5 ms** | 145 / 69 ms | 112 / 179 ms | 119 / 421 ms |

**Per-request kulu, c=1 (kõige puhtam):**

| kiht | kumulatiivne | selle kihi lisa |
|---|---:|---:|
| DB päring ise | 0,28 ms | 0,28 ms |
| + ReSql (HTTP + parse + bind + pool) | 1,4 ms | **~1,1 ms** |
| + Ruuter DSL (2 nested guardi, `validate_input`, `setup`, `check_poll`, sisemine `http.post` → ReSql, `check_local`, `respond_local`) | 11,5 ms | **~10 ms** |

**Ruuter on ~40× DB ja ~8× ReSql** ühe päringu kohta. Ühe lõime lagi ~87 rps;
konkurentsi lisamine läbilaskevõimet ei tõsta (püsib 110–145 rps) — Ruuter on
1,0-tuumaga CPU-seotud. DB 1 rea juures **ei ole tegur** (0,28 ms); tema piir
tuleb alles mahuga (§4c: 23 s @ 1M → ADR-009 C6).

---

## 5. Soovitused

### Paranda benchmark (et numbrid midagi tähendaksid)

1. `bulk-insert-consignments.sql`: `gate_id='EU-EE'`; dokumenteeri, et päring
   peab pärima ID mis on bulk-hulgas (`BULK-<n>`).
2. Mõõda kihte eraldi: `ab` ReSql-i pihta (`:8090/efti/get_consignments`)
   DB+ReSql jaoks; `ab` `authority/search` pihta terve route jaoks.
3. Local-search benchmarkis seed nii et lokaalne alati tabab — või stub
   multiplexer — muidu ootab iga tühi tulemus `forward_to_gates`-i.
4. Commiti `EXPLAIN (ANALYZE, BUFFERS)` artefakt sihtmahu juures.
5. Kasuta `k6` / `wrk`-i ramp'i + think-time'iga ja täieliku latentsuse jaotusega.
6. Kirja täpne keskkond (PG konf, konteinerite CPU/mälu, compose fail).

### Paranda kood / parameetrid (juurpõhjus)

| Mõju | Muudatus | Pingutus | Tõestus |
|---|---|---|---|
| **suurim, ainus päris parandus** | `is_latest boolean` veerg + partial unique index `(platform_id, dataset_id) WHERE is_latest`; eelmine `(platform_id, dataset_id)` rida flipitakse `false`-ks insertimisel. `get_consignments.sql` kirjutatakse ümber: `SELECT ... FROM consignments WHERE is_latest AND status != 'DELETED' AND gate_id = :gateId AND <kriteerium>` — alampäring ja `DISTINCT ON` kaovad. | keskmine, arhitektuurne — **vajab ADR-i**: trigger (`SECURITY DEFINER`) vs insert-SQL-i sees vs archiveri flip, kuna `app` roll on INSERT-only | 23 337 ms → **1.7 ms** (jaotis 4f) |
| alternatiiv B-le | Kui `db_archiver` (roll on olemas, `000-extensions.sql`, aga tööd tegevat jobi/teenust repos veel pole) ehitatakse ja jookseb tihedalt, jäädes tabelisse ainult viimased read → `DISTINCT ON` võib üldse ära jätta. Vahepealsel ajal (mitu rida per dataset) annaks see valesid tulemusi, seega vajab garanteeritud jooksmist. | suur (uus teenus) | — |
| ~~keskmine~~ | ~~ainult sobiv indeks `(platform_id, dataset_id, created_at DESC)`~~ — **ei aita**, muudab hullemaks (jaotis 4e) | — | proovitud, tagasi lükatud |
| väike (koos B-ga mõttetu) | filtrite pushdown alampäringusse enne B-d | — | B teeb selle ebavajalikuks |
| konfig | `ruuter: cpus: '0.5'` → tõsta (koormustestiks kindlasti; prod-is samuti kaaluda); `resql max_connections: 10` → 25–50 | triviaalne | Ruuter 37 req/s vs ReSql otse ~2000 req/s (4a vs 4b) |
| band-aid | PG `work_mem` 4 MB → 256 MB (sort ei spilliks kettale; ei paranda skaleeruvust, B teeb ebavajalikuks) | triviaalne | Sort Method: external merge Disk 245 MB (4c) |

### "vs PoC" enne edasi eskaleerimist

Kaks eraldi asja, ära aja segamini:

1. **DB / päring** — praegu 23 s / 0.14 req/s 1M real. `is_latest` parandus viib
   selle **1.7 ms / ~2000 req/s** peale (ReSql otse). See sulgeb sisuliselt kogu
   "1M real 15 s" probleemi. Kui PoC teeb siin lihtsama indekseeritud mudeli
   (tõenäoline), siis pärast B-d on DB vahe kadunud.
2. **Ruuteri per-request overhead** — `authority/search` Ruuteri kaudu ~37 req/s
   vs ReSql otse ~2000 req/s **0,5 tuumaga**. Osa sellest on CPU-cap (tõsta →
   mitmekordne võit), osa on DSL-i JS-tõlgendamise püsikulu. See on jääk, mille
   suurust saab hinnata alles pärast (a) `is_latest`-i ja (b) CPU-cap'i tõstmist.

**Enne kui "20–120× aeglasem kui PoC" ülespoole liigub:** tee mõõtmine uuesti
`is_latest` + tõstetud CPU-ga, ausa võrdlusega (sama päring, maht, riistvara,
tööriist), ja lahuta DB-vahe Ruuteri-vahest. Praegused numbrid mõõdavad
peamiselt katkist päringut ja poolt tuuma.

---

## 6. Muudatuste logi

Iga samm siia: mida muudeti, miks, mõõdetud efekt.

### 6.1 — Benchmark-stub: `X-Skip-Gate-Forward` (`authority/search.yml`)

**Miks:** empty local search kukub `forward_to_gates`-i (`timeout: 65000`).
Mõõdetud: tühja tulemuse päring **76,8 s → HTTP 500** (65 s timeout +
`stop_in_case_of_exception: true`). See on ka omaette probleem — praegu ei tule
"midagi ei leitud lokaalselt, teised gate'id ei vasta" korral **mingit** kasulikku
vastust 65 s jooksul.

**Muudatus:** `check_local` switch'i lisatud haru — kui päis
`X-Skip-Gate-Forward: true`, tagasta lokaalne tulemus (siin `[]`) +
`x-poll-more: false`, ilma multiplexerit puutumata. Gate-internal päis, päris
kutsuja seda ei saada.

| | tühja päringu latents |
|---|---|
| stub OFF (praegu) | 76,8 s → 500 |
| stub ON | lokaalse päringu aeg (`get_consignments`), multiplexerit vahele jättes |

Nüüd saab `get_consignments`-i koormustesti teha ilma multiplexeri mürata.

**Järgmine:** eraldi otsustada, kas empty-local + no-gate-response peaks
päris­elus ka kohe `[]` + `x-poll-more:true` tagastama (praegu 65 s / 500).

### 6.2 — `EXPLAIN (ANALYZE, BUFFERS)` artefakt

Salvestatud: `docs/askend_performance/explain-1m-current.txt` — praegune
`get_consignments`-i plaan 1M real. Näitab `external merge Disk` Sort node'i ja
`Rows Removed by Filter: 1 000 000`.

### 6.3 — Päringu-parandus: kandidaatide võrdlus

**Piirangud (AGENTS.md → "Database rules"):** *append-only, INSERT-only* (`app`
roll ainult SELECT+INSERT, read'id `DISTINCT ON ... ORDER BY created_at DESC`)
ja **"No JOINs on hot path"** (search-veerud on `consignments`-i peale
denormaliseeritud). Seega:

| Kandidaat | JOIN? | insert-only? | semantika täpne? | 1M plaan / aeg |
|---|:---:|:---:|:---:|---|
Mõõdetud **200 000 real** (1M kukutas selle masina Docker VM-i korduvalt; C0
oli 1M peal 23,3 s — `explain-1m-current.txt`. C1/C6/C3 on indeksi-otsingud →
~konstant tabeli suurusest sõltumata). `work_mem=4MB`, soe cache:

| Kandidaat | JOIN? | insert-only? | semantika täpne? | plaan | aeg @200k |
|---|:---:|:---:|:---:|---|---|
| **C0 praegune** | ei | jah | jah | Parallel Seq Scan + **Sort (external merge kettale)** + Unique(200k) + Filter | **2 775 ms** (~23 s @1M) |
| **C1 filter-first** — kriteerium inner `WHERE`-i, dedupe tabamused | ei | jah | **ei** (vt allpool) | `Index Scan idx_consignments_main_transport_id` → Sort(1) → Unique | **1,2 ms** |
| **C6 `NOT EXISTS`** — filter first + "pole uuemat rida sellele datasetile" | võtmesõna ei, **aga planeerija plaan on `Nested Loop Anti Join`** → "no JOINs" mõtte vastu; meeskonna otsus | jah | jah | Anti Join: Index Scan + `Index Only Scan idx_consignments_dataset_latest` | **0,09 ms** |
| **C3 `is_latest` lipp** — `WHERE is_latest AND <kriteerium>` | ei | **ei** — 1 UPDATE per insert (cache-veerg; `consignments` domeeni­andmed jäävad muutumatuks, flip triggeriga, `app` jääb INSERT-only) | jah | `Index Scan idx_consignments_main_transport_id`, Filter `is_latest` | **0,14 ms** |

Täisplaanid: `docs/askend_performance/explain-candidates-1m.txt`
(skript: `explain-candidates-1m.sql`).

### 6.4 — C1 + C6 koos = **C6** (append-only vastus)

Küsimus oli: kas C1 ja C6 koos annavad efekti, arvestades et baas on append-only?
**Vastus: C6 ongi juba "C1 + C6".** C6 päring filtreerib esmalt kriteeriumi järgi
(= C1 osa, kasutab `idx_consignments_main_transport_id`-i), siis `NOT EXISTS`
kontrollib iga kandidaadi kohta "kas sellele datasetile on uuem rida" (parandab
C1 semantilise vea).

**Semantiline test** (dataset re-uploaditud: `main_transport_id` `AAA` → `BBB`):

| päring | otsing "AAA" (vananenud id) | otsing "BBB"+"CCC" (praegused) |
|---|---|---|
| C0 praegune | `(tühi)` ✓ | `BBB,CCC` ✓ |
| C1 filter-first | **`AAA`** ✗ (vale — vananenud rida) | — |
| **C6 (C1 + NOT EXISTS)** | `(tühi)` ✓ | `BBB,CCC` ✓ |

**C6 omadused:**
- **Append-only puhas** — ei mingit `is_latest` veergu, ei mingit UPDATE-i,
  `app` roll jääb SELECT+INSERT. Sobib append-only mudeliga.
- **Semantiliselt identne C0-ga** (kontrollitud ülal).
- **~0,09 ms** vs C0 2775 ms @200k (23 s @1M). Skaleerub tulemuste arvuga
  (spetsiifiline vessel/container ID → 1–paar kandidaati → ~0,1 ms; lai filter
  → rohkem indeksi-otsinguid, aga endiselt lineaarne tabamuste arvus, mitte
  tabeli suuruses).

**Ainus lahtine punkt:** planeerija plaan on `Nested Loop Anti Join`. AGENTS.md
"No JOINs on hot path" mõte on **mitte joinida `consignments`-i teiste
tabelitega** (`gates`/`platforms`/...) — selleks ongi väljad denormaliseeritud.
Self-korreleeritud anti-join "viimase rea" kontrolliks on append-only logist
praeguse seisu lugemise **olemuslik hind** (variandid: C3 markeri UPDATE / C0
sort kõik / C6 "pole uuemat"). Vaja meeskonna otsust + kirja panna (ADR või
AGENTS.md täpsustus).

**Soovitus:** C6. Kui meeskond `NOT EXISTS` anti-join'i vastu on → C3 (`is_latest`
trigger, väike append-only kompromiss) või kinnitada et identifikaatoriväljad ei
muutu ja võtta C1.

**C1 semantiline risk konkreetselt:** dataset X laaditakse v1 (`main_transport_id
= 'A'`), siis v2 (`'B'`). Otsing `main_transport_id = 'A'`:
- C0: dedupe → v2 (`'B'`) → filter `= 'A'` → **ei tagasta** (õige: "selle dataset'i
  praegune identifikaator pole A").
- C1: filter `= 'A'` → tabab v1 → dedupe → **tagastab v1** (vale — vananenud rida).

Kui identifikaatoriväljad on `dataset_id` eluea jooksul praktikas muutumatud
(re-upload = staatuse-muutus / parandus, mitte uus transpordivahend), on C1
ohutu. **Vajab meeskonna kinnitust.**

Tulemused (`docs/askend_performance/explain-candidates-1m.txt`): `<TODO>`

### 6.5 — Otsus: läheme C6 peale (ADR-009)

Sten otsustas (09.09.2026): `consignments` lugemised lähevad **C6** mustrile
(filter-first + `NOT EXISTS` viimase-rea kontroll), `DISTINCT ON` alampäring
kaob. Vormistatud: [`docs/architecture/decisions/009-consignments-latest-row-not-exists.md`](../architecture/decisions/009-consignments-latest-row-not-exists.md).

Rakendus (järgmine samm):
- `DSL/Resql/efti/POST/get_consignments.sql` ümber C6-le
- üle vaadata `get_consignments_by_transport_means.sql`, `get_consignment_by_id.sql`,
  `get_consignment_xml.sql`, `check_transport_means_registered.sql`
- `AGENTS.md` — "No JOINs on hot path" täpsustus
- `semantic-test.sql` → püsiv test `tests/` alla

### 6.6 — Seemetamine korda + CI-ressursid tõstetud

**Seemetamine** (`docs/askend_performance/seed-consignments.sql`, `run.md`):
- baasrida `VESSEL-001` läheb sisse *päris teed pidi* — `POST sample.xml`
  `/platforms/v1/consignments` peale (xml-mapper + `insert_consignment` teevad
  veergude mapimise). Enne oli see kas puudu või vale `gate_id`.
- `gate_id = 'EU-EE'` (oli Antoni failis `'EE'` — ei läbinud kunagi
  `local_search`-i `gate_id = :gateId` filtrit, iga otsing kukkus multiplexerisse).
- semantilised fikstuurid (`p1` re-upload `AAA→BBB`, üksik `CCC`) SQL-failis.
- verifitseeritud: `VESSEL-001 → 1`, `AAA → 0`, `BBB → 1`, `CCC → 1`.

**CI-ressursid** (`compose.yml` — need on ka CI väärtused, e2e liin jookseb
`compose.yml` pealt):
| teenus | enne | nüüd |
|---|---|---|
| `database` | limiiti polnud | `cpus: '1.0'`, `mem_limit: 1G` |
| `ruuter` | `cpus: '0.5'`, `mem 512M` | `cpus: '1.0'`, `mem 512M` |
| `resql` pool (`resql.yaml max_connections`) | 10 | **75** (PG default `max_connections` on 100 — jäetud puutumata, 25 jääb liquibase / tim / ad-hoc psql jaoks) |

### 6.7 — Hop-latents: ruuter → resql → db (`hop-latency.sh`)

Uus eraldi mõõt (`docs/askend_performance/hop-latency.sh`), järjestikku (`c=1`),
et iga number oleks *ühe päringu maksumus*, mitte järjekorra sügavus. 3 jooksu,
n=2000/etapp, `ruuter cpus: 1.0`, `resql pool 75`, 1 seeditud rida.

| etapp | mean | p50 | p90 | p99 |
|---|---:|---:|---:|---:|
| `ab → ruuter /health/ready` (paljas routing) | 1,8–4,1 ms | 1–2 ms | 3–8 ms | 12–36 ms |
| `ab → resql → db` (`get_consignments`) | 2,1–3,6 ms | 1–2 ms | 4–7 ms | 11–33 ms |
| `ab → ruuter → resql → db` (`authority/search`) | **29–57 ms** | **18–21 ms** | 58–101 ms | 178–552 ms |

**Ruuteri DSL-i püsikulu ≈ 25–50 ms päringu kohta** (p50 ~15–18 ms üle
võrguhüpete ~3 ms). Ühe lõime läbilaskevõime 17–34 req/s.

**Kuhu see aeg kaob** (Ruuteri enda per-step log, 300 järjestikust päringut):

| samm | keskm. | max | märkus |
|---|---:|---:|---|
| `check_service_token` (guard switch, ~2× päringu kohta) | 23 ms | 5360 ms | *puhas stringivõrdlus* |
| `local_search` (sisemine `http.post` → resql) | 67 ms | 3020 ms | **sama päring otse `ab`-ist = 2 ms** |
| `setup` (assign, 2 muutujat) | 10 ms | 191 ms | |
| `check_local` (1 switch) | 10 ms | 194 ms | |
| `respond_local` (return) | 8 ms | 87 ms | |
| `check_poll`, `validate_input` (switch) | ~5 ms | ~90 ms | |

**Järeldus:** realistliku mahu juures (1 rida) **DB on ~2 ms ega ole
pudelikael** — pudelikael on Ruuteri DSL-i täitmine, eelkõige (a) sisemine
HTTP-hüpe ReSql-i (`local_search`, ~5–30× kallim kui sama kutse väljastpoolt) ja
(b) guard'i avaldise-hindamine. Kõik DSL-sammud maksavad 5–70 ms, kuigi peaksid
olema alla millisekundi → Ruuteri avaldise-mootor / HTTP-klient CPU-surve all.
`cpus 0.5 → 1.0` ei kaotanud kõikumist (7 ms vs 75 ms järjestikused päringud) —
osuti on avaldise-mootoris ja HTTP-kliendis, mitte toores CPU-kvoot.

> NB: masin jooksutab paralleelselt muud → absoluutnumbrid mürarikkad (10 s
> üksik­piigid). Kihtide **vahe** (DB 2 ms vs Ruuter 30 ms) on stabiilne kõigis
> jooksudes.

**Kaks eraldi telge, ära aja segi:**
1. **maht** — `get_consignments` 1M real 23 s (§4c) → C6 ~0,1 ms (§6.4, ADR-009).
2. **Ruuteri per-request** — `authority/search` ~30 ms / ~30 req/s ühe lõimega,
   mahust sõltumatu. C6 seda ei paranda. Kandidaadid: Ruuteri `http.post`
   keep-alive / pool ReSql-i suunas; guard'i lihtsustus; vähem DSL-samme
   kuumal teel.

### 6.8 — Ruuter 0.9.12-rc → 0.9.14-rc + Ruuter #79 (@sviljus leid)

**Versioonikontroll** (`/code/Ruuter`, `/code/Resql`):
- Ruuter: viimane on **0.9.14-rc** (efti oli 0.9.12-rc). Vahepeal 0.9.13-rc + 0.9.14-rc,
  mõlemad puhtad fixid. `docker/ruuter/Dockerfile`, `docker/ruuter-xroad-mock/Dockerfile`
  → **0.9.14-rc**.
- ReSql: viimane väljalase on endiselt **0.2.0-alpha** (efti on sellel). `dev`-is on üks
  taggimata fix (#27 `password_env` vs URL-i userinfo) — efti kasutab credential-free
  URL-i + `password_env`, seega ei puuduta.
- Docker Hub oli bumpi ajal maas (`registry-1.docker.io ... EOF`) → 0.9.14-rc pilti
  ei saanud tõmmata; ka lokaalne build `/code/Ruuter`-ist kukkus (crates.io
  kättesaamatu build-konteinerist, TLS connect error). **4j numbrid on 0.9.12-rc
  pealt.** CI tõmbab 0.9.14-rc; kordan mõõtmise, kui võrk taastub. 0.9.13/0.9.14
  on korrektsusfixid, mitte jõudlustöö → 4j kihtide vahe ei muutu oluliselt.

**Ruuter #79** — `guard → template: → sama guard` lõpmatu rekursioon, protsessi crash
(exit 134) 0.9.11–0.9.12-rc korral. **Reporter: @sviljus.** Parandatud **0.9.13-rc**:
per-request guard-stack (juba jooksev guard jäetakse vahele) + `MAX_GUARD_DEPTH = 32`.

Mõju efti-le: efti guard-failid (`efti/POST/api/v1/.guard.yml`,
`efti/POST/api/v1/authority/.guard.yml`, …) on `switch`-põhised, **ei kutsu `template:`**,
seega polnud crash'i teel. Kontrollitud Ruuteri lähtekoodist
(`src/steps/template.rs`, `src/router/mod.rs`): route-kehas olev `template:` samm käivitab
sihtmärgi guardi ikka (par-guard'i stackil pole, sest entry-guardid on juba pop'itud) →
`-xml` / `authority/*` route'ide template-sammudel **jääb `x-internal-service-token`
edasi­saatmine load-bearing'iks**, mitte üleliigseks. Guard-failidesse lisatud märge
leiu + fix-versiooniga.

### 6.9 — authority/search: local-first + broadcast, ei blokeeru enam (otsused 2/3/4)

**Otsus 2 — `docker/dsl-tools/` maha.** 0.9.14-rc pildis on `dsl-lint` / `dsl-test`
(`/usr/local/bin/`, Ruuter #83). `docker/dsl-tools/Dockerfile` kustutatud;
`.github/workflows/e2e.yml` + `.gitlab-ci.yml` jooksutavad tööriistu otse
`turnerrainer/ruuter:0.9.14-rc` pildist (`docker run --rm -v "$PWD:/workdir" ... dsl-lint …`).

**Otsus 3 — topeltguard maha.** `efti/POST/api/v1/authority/.guard.yml` ja
`efti/GET/api/v1/authority/.guard.yml` said `declaration.override_ancestors: true` —
mõlema kontroll on ancestor-guardiga (`efti/{POST,GET}/api/v1/.guard.yml`) täpselt
identne, seega `check_service_token` ei jookse enam kaks korda iga `authority/*`
päringu kohta (§4j: guard'i-samm oli mõõdetavalt kallis).

**Otsus 4 — `authority/search` ei blokeeru.** "local-first, then broadcast":

*Enne:* lokaalne miss → `forward_to_gates` = **blokeeriv** `POST /api/v1/first/:id`
(`timeout: 65000`); ükski gate ei vasta → 65 s → HTTP 500.

*Nüüd:*
- `local_search` alati.
- `criteria_to_xml` → `start_broadcast` = **mitte-blokeeriv** `POST /api/v1/search/:id`
  (multiplexer registreerib otsingu, fännib teistele gate'idele taustal, tagastab kohe).
- `respond_local` → tagastab **selle gate'i tulemuse kohe** (`[]` või read) +
  `x-poll-more: true`.
- Teiste gate'ide tulemused: klient kordab päringut `X-Request-Id` + `X-Poll` +
  `{}` kehaga → `poll_remaining` = `GET /api/v1/rest/:id`.

**Multiplexer** (`code/multiplexer/src/MultiplexerRoutes.kt`):
- `POST /api/v1/first/:id` (blokeeriv `poll(63s)` + 504) **eemaldatud**, asendatud
  `POST /api/v1/search/:id`-ga (kohene 202, fan-out `AppScope.async`-is).
- `GET /api/v1/rest/:id` = piiratud long-poll: kui midagi pole veel saabunud ja
  gate'id veel vastavad, ootab esimest kuni 10 s (mitte tühja kohe); muidu
  tagastab kohe. **Kunagi 500.** Tundmatu `searchId` → tühi + `x-poll-more:false`.
- Testid uuendatud, `multiplexer:test` roheline.

**`X-Skip-Gate-Forward` benchmark-stub (§6.1) eemaldatud** — päris tee vastab nüüd
kohe, stubi pole vaja.

**xroad/POST/v1/search.yml:** `timeout: 70000 → 15000` (core ei blokeeru enam),
`x-poll: ${incoming.body.poll}` päis lisatud (kannab `{"poll": true}` int[ent]i core'i
`check_poll`-ini; null-väärtus kukub http-sammus välja, Ruuter #57).

**Testimuudatused:** `tests/authority/authority-api.http` — lokaalne tabamus annab
nüüd `x-poll-more: true` (mitte `false`); lokaalse miss'i esmavastus on `[]` kohe;
teise gate'i tulemus (`MOCK-123` / `EU-MOCK`) tuleb `X-Poll` päringus.
`tests/authority/xroad-forward.http` — muudatust ei vaja (ei kontrolli `x-poll-more`-i).

### 6.10 — Ruuteri sisemine `http.post` (otsus 1: mida saab olemasoleva koodiga)

Vt eraldi kokkuvõte — Ruuteri per-request overhead'i (§6.7: `local_search` sisemine
hüpe ~5–30× kallim kui väline kutse) saab olemasoleva koodibaasi juures leevendada:
DSL-samme kuumal teel vähem (otsus 3 võttis guard'i-topelduse; `start_broadcast`
lisab ühe sammu aga kaotab 65 s blokeeringu), `ruuter cpus` tõstetud (§6.6). Ruuteri
`http.post` connection-reuse ReSql-i suunas on Ruuteri-poolne, mitte efti DSL — see
läheb Ruuteri arendajatele (eraldi issue).
