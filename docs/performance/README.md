# Performance — eFTI Gate (EE)

Konsignatsioonide otsingu (`authority/search`) jõudluse dokumendid, **tehtud järjekorras**:

## 1. Pikkeri testid + tulemused

| Fail | Sisu |
|---|---|
| [`performance-report.md`](performance-report.md) | Pikkeri `ApacheBench` koormustest ja tulemused: `authority/search` ~70–93 req/s, p99 kuni 4 300 ms; 1M real 0,6 req/s / 15 000 ms. Riistvara: Intel Core Ultra 9 386H, 16 tuuma. Järeldus: ~20–120× aeglasem kui PoC. |
| [`bulk-insert-consignments.sql`](bulk-insert-consignments.sql) | Pikkeri 1M-rea seed (DO-loop). |
| [`search.json`](search.json) | Otsingu keha (`mainTransportId = VESSEL-001`). |

## 2. Askendi poolne testide verify

`askend_perf_verification/` kaustas.

| Fail | Sisu |
|---|---|
| [`askend_perf_verification/analysis.md`](askend_perf_verification/analysis.md) §1–§3 | Mida Pikker mõõtis, **mida numbrid tegelikult peegeldasid** (seed ei vastanud päringule; `authority/search` ei ole DB-päring; ressursipiirangud; `ab -c 250` mõõdab küllastust), ja tuvastatud **juurpõhjus** (`get_consignments.sql` kogu-tabeli `DISTINCT ON` sort). |
| [`askend_perf_verification/feedback/2-notes-for-pikker.md`](askend_perf_verification/feedback/2-notes-for-pikker.md) | Lakooniline kokkuvõte Pikkerile — 5 tuvastatud tegurit + metoodika-soovitused. |
| [`bulk-insert-consignments-fixed.sql`](bulk-insert-consignments-fixed.sql) | **Parandatud seed:** `gate_id 'EE' → 'EU-EE'` (vana ei läbinud kunagi `authority/search`-i `EU-EE` filtrit) + garanteeritud `VESSEL-001` rida. Pikkeri originaali ei muudetud. |

## 3. Askendi poolsed muudatused

| Viide | Sisu |
|---|---|
| [`../architecture/decisions/009-consignments-latest-row-not-exists.md`](../architecture/decisions/009-consignments-latest-row-not-exists.md) | **ADR-009** — `get_consignments.sql` kogu-tabeli `DISTINCT ON` sort → filter-first + korreleeritud `NOT EXISTS`. PR #144. |
| [`../architecture/decisions/010-authority-search-non-blocking.md`](../architecture/decisions/010-authority-search-non-blocking.md) | **ADR-010** — `authority/search` mitte-blokeeriv; multiplexeri `/first` → `/search`; kattuvate guard'ide dedup; Ruuter 0.9.12 → 0.9.14. PR #145. |
| [`askend_perf_verification/analysis.md`](askend_perf_verification/analysis.md) §4–§6 | Kandidaatide võrdlus, muudatuste logi, iga sammu mõõdetud efekt. |
| konfiguratsioon | `ruuter` + `database` `cpus: 0.5 → 2.0`; ReSql pool `10 → 75` (PR #145). |

## 4. Tulemused peale muudatusi

| Fail | Sisu |
|---|---|
| [`askend_perf_verification/askend_performance_testing_and_tuning.md`](askend_perf_verification/askend_performance_testing_and_tuning.md) | **Peamine tulemusdokument.** Mõõtmisvoorud, muudatused + efekt, kihtide kaupa enne/pärast, `EXPLAIN` enne/pärast, `authority/search` voo mermaid-diagrammid, `ab` sweep tabelid, `k6` realistlik profiil, kokkuvõttev tabel. |
| [`askend_perf_verification/askend_perf_testing_eng.md`](askend_perf_verification/askend_perf_testing_eng.md) | Ingliskeelne lühikokkuvõte (edastatav). |
| [`askend_perf_verification/analysis.md`](askend_perf_verification/analysis.md) §7 | Kokkuvõttev mõõtmine, kõik parandused peal. |
| [`askend_perf_verification/feedback/3-joudlusparandused-askend.md`](askend_perf_verification/feedback/3-joudlusparandused-askend.md) | "Jõudlusparandused — Askendi poolt" (tellijale). |

### Peamised numbrid (sama masin)

| Näitaja | Enne (Pikker / algne seadistus) | Pärast |
|---|---|---|
| `get_consignments` 1M real | 23 337 ms (`EXPLAIN`) / 0,14 req/s | **0,22 ms** / ~6 000 req/s |
| `authority/search` läbilaskevõime | ~37–93 req/s (lapik) | **~550 req/s** (skaleerub) |
| `authority/search` p99 koormuse all | 1 800–4 500 ms | **~330–420 ms** (`ab`) / **~30–57 ms** (`k6` realistlik) |
| tühja lokaalse otsingu latents | 65–77 s → HTTP 500 | kohe `[]` |

## Kordustootmise skriptid

`askend_perf_verification/`: `run.md`, `seed-consignments.sql`, `bulk-insert-1m.sql`,
`hop-latency.sh`, `k6-authority-search.js`, `semantic-test.sql`, `explain-*.txt`.
