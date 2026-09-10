# Notes for Pikker — consignments search, koormustestide järelkontroll

Esialgsete `authority/search` koormusmõõtmiste (`docs/performance/performance-report.md`)
kordamine. Tulemused ei kordunud täielikult; osa hälbest tuli testi­seadistusest,
osa on tegelik jõudlusküsimus, mis on lahendatud.

## Tuvastatud tegurid

1. **Testandmed ei vastanud päringule.** `bulk-insert-consignments.sql`: `gate_id = 'EE'`,
   kuid `authority/search` filtreerib `gate_id = 'EU-EE'`; `main_transport_id = 'BULK-<i>'`,
   kuid otsitakse `VESSEL-001`. 1M-rea test mõõtis seega tulemuseta päringut, mis jooksis
   edasi multiplexerisse, mitte ühe rea leidmist miljoni seast.

2. **`authority/search` ei ole üksik DB-päring.** Tulemuseta lokaalse otsingu järel
   jätkub voog: xml-mapper → multiplexer (`timeout` 65 000 ms) → xml-mapper → vastus.
   Väravate mittevastamisel tagastati `stop_in_case_of_exception` tõttu HTTP 500 —
   65 sekundi järel. Üks mõõdetud number sisaldas nelja teenust ja timeout'i.

3. **Ressursipiirangud alla sihttaseme.** `resql.yaml` `max_connections: 10`;
   `compose.yml` `ruuter: cpus: '0.5'`. Kõrge konkurentsuse juures on p99 valdavalt
   ühenduste järjekord, mitte päringu töö.

4. **Andmebaasipäring — tegelik viga.** `get_consignments.sql` sisemine
   `SELECT DISTINCT ON (platform_id, dataset_id) * FROM consignments ORDER BY ...` ilma
   `WHERE`-ta materialiseerib viimased-per-dataset read kogu tabeli ulatuses enne
   kriteeriumite rakendamist. 1M real: Seq Scan → external-merge Sort (245 MB kettale) →
   Unique(1M) → filter. Täitmisaeg 23 337 ms.

5. **Koormusprofiil.** `ab -c 250` ilma ramp-up'i ja think-time'ita mõõdab küllastust,
   mitte latentsi realistliku koormuse all.

## Tehtud muudatused (PR-id, CI roheline)

| Muudatus | Viide |
|---|---|
| Päring: `DISTINCT ON` kogu-tabeli sort → filtreeri esmalt + korreleeritud `NOT EXISTS`. Sama tulemus, sama append-only mudel. | PR #144, ADR-009 |
| `authority/search` mitte-blokeeriv: lokaalne tulemus kohe, teised väravad taustal (klient kogub polliga). Multiplexeri `/first` → `/search` + piiratud long-poll. | PR #145, ADR-010 |
| Ruuter 0.9.12-rc → 0.9.14-rc; kattuvate guard'ide dedup. | PR #145 |
| Konfiguratsioon: `ruuter` + `database` `cpus: 2.0`; ReSql pool 75. Testi­seed korrigeeritud. | PR #145 |

## Tulemused pärast muudatusi (sama masin)

**`get_consignments` 1M real:** 23 337 ms → **0,22 ms** (`EXPLAIN`: Nested Loop Anti Join +
kaks indeks-otsingut). ReSql otse: 0,14 → ~5 900 req/s.

**`ab` sweep (serveri lagi):** `authority/search` ~37 → **~550 req/s** (skaleerub);
p99 koormuse all 1 800–4 500 ms → ~330–420 ms.

**`k6` (ramp kuni 100 VU, think-time 0,5–1,5 s, ~30 000 päringut, 0 viga):**

| stsenaarium | p50 | p95 | p99 |
|---|---:|---:|---:|
| `authority/search`, 1 rida | 6 ms | 16 ms | 30 ms |
| `authority/search`, 1M rida | 7 ms | 21 ms | 57 ms |
| ReSql otse | 2 ms | 6 ms | 40 ms |

## Soovitused koormustesti metoodikaks

1. Seed peab päringu tingimustele vastama (`gate_id`, otsitav identifikaator andmehulgas).
2. Mõõta kihtide kaupa: `ab` ReSql-i pihta (DB + päringukiht); `ab` `authority/search`
   pihta (terve marsruut); `pgbench` (DB üksi).
3. Realistlik profiil `k6` / `wrk`-ga: ramp + think-time, latentsi jaotus (p50/p90/p95/p99).
4. Dokumenteerida keskkond: Ruuteri versioon, konteinerite `cpus`/`mem`, ReSql pool,
   PostgreSQL `work_mem` / `shared_buffers`, compose-fail.

Skript ja täisanalüüs: `docs/performance/askend_perf_verification/` (`k6-authority-search.js`, `analysis.md` §7).
