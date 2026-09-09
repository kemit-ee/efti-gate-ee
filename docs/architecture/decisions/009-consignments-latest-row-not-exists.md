# ADR-009: `consignments` viimase versiooni valik `NOT EXISTS`-iga, mitte `DISTINCT ON`-iga

**Otsus (Sten Viljus, 09.09.2026):**

## Otsus

`consignments` lugemispäringutes (`get_consignments.sql` ja teised `FROM consignments`
lugemised) asendatakse muster

```sql
FROM (
  SELECT DISTINCT ON (platform_id, dataset_id) *
  FROM consignments
  ORDER BY platform_id, dataset_id, created_at DESC
) latest
WHERE <kriteeriumid>
```

mustriga, kus kriteeriumid filtreeritakse **enne** viimase versiooni kontrolli ja
"kas see rida on viimane" tehakse korreleeritud `NOT EXISTS`-iga (analüüsi kandidaat **C6**):

```sql
SELECT <veerud>
FROM consignments c
WHERE c.status != 'DELETED'
  AND (:gateId IS NULL OR c.gate_id = :gateId)
  AND <kriteeriumiplokid c.* vastu>
  AND NOT EXISTS (
    SELECT 1 FROM consignments c2
    WHERE c2.platform_id = c.platform_id
      AND c2.dataset_id  = c.dataset_id
      AND c2.created_at   > c.created_at
  )
ORDER BY c.created_at DESC
LIMIT COALESCE(:limit, 100) OFFSET COALESCE(:offset, 0);
```

## Põhjus

### Praeguse päringu probleem

`DISTINCT ON (platform_id, dataset_id)` ilma `WHERE`-ta sisemises alampäringus sunnib
PostgreSQL-i **materialiseerima kogu tabeli** viimased-per-dataset read enne, kui
ükski kriteerium rakendub. 1 000 001 real (mõõdetud, `docs/askend_performance/explain-1m-current.txt`):

- `Parallel Seq Scan on consignments` — kogu tabel, ~4,1 s
- `Sort` `(platform_id, dataset_id, created_at DESC)` — `external merge Disk`, ~245 MB kettale
  (`work_mem = 4MB`), ~17,6 s — **pudelikael**
- `Subquery Scan` — kriteeriumifilter jookseb **viimasena**, materialiseeritud 1M rea peal,
  `Rows Removed by Filter: 1 000 000`; `idx_consignments_main_transport_id` jääb kasutamata
- **Execution Time: 23 337 ms ühe rea tagastamiseks**

Päring on lineaarne tabeli suuruse suhtes ja ei kasuta ühtegi kriteeriumi-indeksit.

### Miks C6

Mõõdetud kandidaadid (200 000 rida, soe vahemälu, `work_mem = 4MB`;
`docs/askend_performance/explain-candidates-1m.txt`):

| Kandidaat | Plaan | Aeg |
|---|---|---|
| C0 praegune (`DISTINCT ON` + alampäring) | Parallel Seq Scan + Sort external merge Disk + Unique + Filter | 2775 ms (~23 s @ 1M) |
| C1 filter-first (ainult kriteeriumi-indeks, ilma viimase-rea kontrollita) | Index Scan → Sort(1) → Unique | 1,2 ms |
| **C6 filter-first + `NOT EXISTS`** | **Nested Loop Anti Join (Index Scan + Index Only Scan `idx_consignments_dataset_latest`)** | **0,09 ms** |
| C3 `is_latest` lipuveerg + trigger | Index Scan + Filter `is_latest` | 0,14 ms |

- **C1 on semantiliselt vale.** Semantiline test (`docs/askend_performance/semantic-test.sql`):
  dataset laaditakse uuesti üles `AAA` → `BBB`. Otsing `AAA` järgi:
  - C0 → tühi ✓ (õige — `AAA` pole enam viimane versioon)
  - C1 → `AAA` ✗ (tagastab aegunud rea, sest viimase-versiooni kontroll puudub)
  - C6 → tühi ✓
  - Otsing `BBB` + `CCC` (üheversioonilise) järgi: C6 → mõlemad ✓
- **C3 nõuab UPDATE-i.** `is_latest` cache-veerg tähendab iga `INSERT`-i kohta ühte
  `UPDATE`-i eelmisele reale (trigger). See on append-only baasi mõttes lisamehhanism ja
  täiendav kirjutuskoormus; `app` roll vajaks siis ka `UPDATE` õigust või peaks trigger
  jooksma `SECURITY DEFINER`-ina. Kõrvale jäetud kui viimane variant, mitte esimene.
- **C6 säilitab append-only mudeli täielikult:** ei lisa veergu, ei tee UPDATE-i,
  `app` roll jääb `SELECT, INSERT`. Semantiliselt identne C0-ga. Ainus lahtine punkt oli,
  kas planeerija realiseerib `NOT EXISTS`-i tõhusalt — realiseerib, `Nested Loop Anti Join`-ina,
  mida teenindab olemasolev `idx_consignments_dataset_latest (dataset_id, platform_id, created_at DESC)`.

### "Hot path'il JOIN-e ei ole" — täpsustus

AGENTS.md reegel "No JOINs on hot path" on suunatud **ristühendustele** — `consignments`
ei ühendata `gates` / `platforms` / muude tabelitega; otsinguveerud on `consignments`-ile
denormaliseeritud. C6 `NOT EXISTS` on **korreleeritud alampäring sama tabeli vastu**
append-only viimase-rea semantika jaoks, mitte SQL `JOIN` võtmesõna ega ristühendus.
Planeerija anti-join on üherealine indekssond (`Index Only Scan`), mitte materialiseeritud
ühendus. See reegli sõnastus lisatakse AGENTS.md-sse.

## Skeem

Muutusi andmebaasi skeemis **ei ole**:
- veerge ei lisata ega eemaldata
- indekseid ei lisata (C6 kasutab olemasolevat `idx_consignments_dataset_latest`-i ja
  kriteeriumi-indekseid nagu `idx_consignments_main_transport_id`)
- rollide õigused muutumata: `GRANT SELECT, INSERT ON consignments TO app`

Märkus: `idx_consignments_dataset_latest` on `(dataset_id, platform_id, created_at DESC)` —
`NOT EXISTS` korrelatsioonile (`platform_id`, `dataset_id`, `created_at`) sobib, sest kõik
kolm veergu on indeksis olemas (`Index Only Scan`). Kui mõõtmised näitavad, et
`(platform_id, dataset_id, created_at DESC)` järjekord annab parema plaani, tehakse see
eraldi changeset'iga; praegu pole vaja.

## Käitumisreeglid

- Kõik `FROM consignments` **lugemised** kasutavad filter-first + `NOT EXISTS` mustrit.
- Kriteeriumiplokid rakenduvad `consignments`-i põhitabelile (`c.*`), mitte
  materialiseeritud alamhulgale.
- `WHERE status != 'DELETED'` jääb (pehme kustutus = uus rida, ADR-002 loogika).
- `NOT EXISTS` võrdleb `created_at > c.created_at` — range `>`, et rida ise ei diskvalifitseeriks
  end. Kui kaks rida sama `(platform_id, dataset_id)` jaoks jagavad täpselt sama `created_at`-i
  (praktikas ei juhtu, `created_at` on `default now()` mikrosekundi täpsusega), tagastaksid
  mõlemad — sama piirjuhtum kui `DISTINCT ON`-il, kus valik oleks suvaline.
- `ORDER BY` muutub `platform_id, dataset_id, created_at DESC` → `created_at DESC`
  (alamhulk on juba per-dataset unikaalne, `DISTINCT ON` järjekorranõuet enam pole).

## Rakendatav changeset

SQL-failide ümberkirjutus (mitte Liquibase — ReSql `.sql` endpoint'id):

- `DSL/Resql/efti/POST/get_consignments.sql` — põhipäring, C6 muster
- Üle vaadata ja sama mustrisse viia:
  - `DSL/Resql/efti/POST/get_consignments_by_transport_means.sql`
  - `DSL/Resql/efti/POST/get_consignment_by_id.sql`
  - `DSL/Resql/efti/POST/get_consignment_xml.sql`
  - `DSL/Resql/efti/POST/check_transport_means_registered.sql`
  - `DSL/Resql/efti/POST/soft_delete_consignment.sql` (kui loeb enne kirjutamist)
- `AGENTS.md` — "No JOINs on hot path" täpsustus (korreleeritud anti-join sama tabeli vastu
  append-only semantika jaoks on lubatud)
- Semantiline regressioonitest `docs/askend_performance/semantic-test.sql` viia
  `tests/`-i alla püsivaks testiks

Mõõtmised ja taastootmine: `docs/askend_performance/analysis.md` §6.4,
`docs/askend_performance/explain-candidates-1m.{sql,txt}`.
