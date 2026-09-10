# Jõudlusparandused — enne / pärast

Konsignatsioonide otsingu koormustestide järelkontroll. Kõik mõõtmised sama arendaja­masina
peal, sama tööriistadega. Täisanalüüs: [`analysis.md`](analysis.md).

## Muudatused

| # | Muudatus | Kus | Vajab kinnitust? |
|---|---|---|---|
| 1 | **`get_consignments.sql` — päringu ümberkirjutus.** Kogu-tabeli `SELECT DISTINCT ON (...)` sort → filtreeri esmalt + korreleeritud `NOT EXISTS` "sellele datasetile pole uuemat rida". Sama tulemus, sama append-only mudel. | ADR-009 | **Ei** — semantiliselt identne (tõestatud testiga) |
| 2 | **`authority/search` — mitte-blokeeriv.** Lokaalne vaste kohe; kui pole, kohe `[]` + broadcast taustal (klient kogub polliga). Multiplexeri `/first` (blokeeriv 63 s) → `/search` (kohene) + piiratud long-poll `/rest`. | ADR-010 | Jah — käitumis­muutus |
| 3 | **Ruuter 0.9.12-rc → 0.9.14-rc**, kattuvate guard'ide dedup, CI-tööriistad Ruuteri image'st. | ADR-010 | Jah |
| 4 | **Konfiguratsioon:** `ruuter` + `database` `cpus: 2.0` (oli 0,5), ReSql pool `10 → 75`. Testi-seed korda (`gate_id='EU-EE'`, `VESSEL-001` bulk-hulka). | — | Jah (CI/testi konf) |

## `get_consignments` päring 1M real (`EXPLAIN ANALYZE`)

| | **enne** (`DISTINCT ON`) | **pärast** (`NOT EXISTS`, ADR-009) |
|---|---|---|
| plaan | Parallel Seq Scan → Sort **external merge Disk 245 MB** → Unique(1M) → Filter | Nested Loop Anti Join → Index Scan + Index Only Scan (Heap Fetches 0) |
| **täitmisaeg** | **23 337 ms** | **0,22 ms** |
| I/O | ~55 000 lehte + 245 MB temp | 16 buffer hit |
| ReSql otse (`ab`, c=20) | 0,14 req/s (30 s timeout'id) | ~5 900 req/s |

Artefaktid: [`explain-1m-current.txt`](explain-1m-current.txt) (enne),
[`explain-c6-1m.txt`](explain-c6-1m.txt) (pärast).

## `authority/search` läbilaskevõime ja latents

### `ab` sweep (serveri tegelik lagi, burst ilma pausita)

| `-c` | **enne** req/s | **enne** p99 | **pärast** req/s | **pärast** p99 |
|---:|---:|---:|---:|---:|
| 1 | ~37 | ~60 ms | **242** | 10 ms |
| 10 | ~37 | ~400 ms | **508** | 45 ms |
| 50 | ~37 | ~2 500 ms | **552** | 204 ms |
| 100 | ~37 (lapik) | ~4 500 ms | **557** | 418 ms |

Enne: läbilaskevõime **lapik ~37 req/s** sõltumata konkurentsusest (Ruuter 0,5 tuuma + pool 10);
`-c` kasv kasvatas ainult järjekorda. Pärast: **skaleerub ~550 req/s-ni**.

### `k6` — realistlik profiil (ramp kuni 100 VU, think-time 0,5–1,5 s, ~30 000 päringut, 0 viga)

| stsenaarium | p50 | p90 | p95 | **p99** | max |
|---|---:|---:|---:|---:|---:|
| `authority/search`, 1 rida | 6,1 ms | 11,8 ms | 15,8 ms | **29,5 ms** | 381 ms |
| `authority/search`, **1M rida** | 6,8 ms | 14,6 ms | 21,2 ms | **56,6 ms** | 383 ms |
| ReSql otse `get_consignments`, 1 rida | 2,4 ms | 4,3 ms | 5,5 ms | **40,3 ms** | 715 ms |

Realistliku koormuse all on p99 **kümneid millisekundeid**, mitte sekundeid — `ab -c 250`
näitas järjekorra-ootust küllastuspunktis. Andmemahu kasv 1 → 1M kirjet tõstab p99
~30 → ~57 ms; **andmebaas ei ole kitsaskoht üheski mahus**.

## Kokkuvõte Antoni algnumbritega

| | Anton (16-tuumaline) | algne mõõt (0,5 vCPU, vana SQL, blokeeriv) | **pärast parandusi** |
|---|---|---|---|
| `authority/search` läbilaskevõime | 70–93 req/s | ~37 req/s (lapik) | **~550 req/s** (skaleerub) |
| `authority/search` p99 koormuse all | 1 800–4 300 ms | 2 500–4 500 ms | **~330–420 ms** (`ab`) / **~30–57 ms** (`k6` realistlik) |
| `get_consignments` 1M real | 0,6 req/s | 0,14 req/s (timeout'id) | **~6 000 req/s** (0,2 ms/päring) |
| tühja lokaalse otsingu latents | — | 65–77 s → HTTP 500 | kohe `[]` + `x-poll-more` |

**Jääk:** Ruuteri DSL-mootor lisab ~3 ms päringu kohta (~5,6×, mitte enam ~50×) — Ruuteri,
mitte efti kood; skaleerub konkurentsiga. Päris serveris (rohkem tuumi) väiksem.
