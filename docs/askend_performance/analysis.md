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
- `ruuter` jäetud `cpus: '0.5'` peale.
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
