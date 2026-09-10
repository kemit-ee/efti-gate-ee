# consignments search — testide lühikokkuvõte

*Sisemine. PR #144 (SQL-päring) + PR #145 (otsinguvoog); kombineeritud haru `perf/consignments-search-analysis`.*

## Lühikokkuvõte

Pikkeri koormustestid näitasid, et consignments search on **20–120× aeglasem kui
Digilogistika PoC**. Analüüsisin numbrid lahti: seal oli **üks päris jõudlusviga**, aga
ülejäänud "aeglus" tuli **katkisest testi-seadistusest ja konfiguratsiooni-lagedest**, mitte
koodist. Kõik on nüüd parandatud ja üle mõõdetud.

**Tulemus:** DB-päring 1M real **23 sekundit → 0,2 millisekundit** (~100 000×). Otsingu
läbilaskevõime **~37 → ~550 päringut/s** ja skaleerub. Otsing ei jää enam kunagi 65
sekundiks kinni ega anna HTTP 500.

## Mis oli katki

| # | Probleem | Kelle | Kas päris viga? |
|---|---|---|---|
| 1 | `get_consignments.sql` päring sorteeris **kogu tabeli** iga otsingu jaoks — 1M real 23 s, 245 MB kettale | kood (juurpõhjus) | **Jah** |
| 2 | Testi-seed oli vale: `gate_id = 'EE'` (peab olema `EU-EE`), otsitav `VESSEL-001` puudus bulk-andmetest | test | ei, aga peitis #1 |
| 3 | `authority/search` tühja tulemuse korral ootas 65 s teisi väravaid ja andis siis HTTP 500 — üks testinumber segas 4 teenust + timeout'i kokku | kood (disain) | **Jah** |
| 4 | ReSql ühenduste pool = 10, Ruuter CPU-lagi 0,5 tuuma | konfiguratsioon | ei, konfi |
| 5 | Ruuter oli 2 versiooni maas (sh minu enda raporteeritud crash-bug parandamata) | sõltuvus | pool-jah |
| 6 | Kaks kattuvat identset turvakihti (guard'i) jooksid iga otsingu kohta | kood (raiskamine) | väike |

Pikkeri test **oleks #1 kinni püüdnud**, kui seed oleks päringule vastanud — praegu mõõtis
1M-rea test tegelikult "skaneeri miljon rida → ära leia midagi → kuku multiplexerisse".

## Mis sai tehtud (haru `perf/consignments-search-analysis`)

**A. Päringu ümberkirjutus (juurpõhjus, ADR-009).** `DISTINCT ON`-i kogu-tabeli sort
asendatud filtreeri-esmalt + `NOT EXISTS` mustrilt. Append-only mudel puutumata,
semantiliselt identne (tõestatud testiga). **23 337 ms → 0,22 ms** @ 1M rida.

**B. Otsinguvoog ei blokeeru (ADR-010).** Lokaalne vaste tuleb kohe; kui pole, tuleb kohe
tühi vastus ja teised väravad päritakse taustal (klient küsib tulemused polliga). Enne:
65 s ootel → HTTP 500.

**C. Ruuter 0.9.12 → 0.9.14** (sh minu raporteeritud crash-bug'i parandus), topeltguard
eemaldatud, CI-tööriistad tulevad nüüd Ruuteri image'st (üks Dockerfile vähem).

**D. CI/testi-konfiguratsioon:** `ruuter` + `database` `cpus: 2.0` (oli 0,5), ReSql pool
`10 → 75`. Seed korda: `gate_id = 'EU-EE'`, `VESSEL-001` läheb sisse päris teed pidi.

Kõik verifitseeritud: täis e2e testid **208/208 roheline**, DSL-lint 78/78, ühikutestid
rohelised.

## Numbrid

| | Pikker (16-tuumaline masin) | algne mõõt (0,5 vCPU, vana SQL) | **pärast parandusi** |
|---|---|---|---|
| otsingu läbilaskevõime | 70–93 req/s | ~37 req/s (lapik) | **~550 req/s** (skaleerub) |
| otsingu p99 koormuse all | 1 800–4 300 ms | 2 500–4 500 ms | **~330–420 ms** |
| DB-päring 1M real | 0,6 req/s | 0,14 req/s (timeout'id) | **~6 000 req/s** (0,2 ms) |
| tühja otsingu latents | — | 65–77 s → HTTP 500 | kohe vastus |

Realistliku koormuse all (k6, ramp + think-time kuni 100 kasutajat, ~30 000 päringut,
0 viga): `authority/search` **p99 ~30 ms** (1 kirje) / **~57 ms** (1M kirjet). `ab -c 250`
näitas 300–4500 ms, sest mõõtis järjekorra-ootust küllastuspunktis, mitte latentsi.

Jääk: Ruuteri DSL-mootor lisab ~3 ms päringu kohta (~5,6×, mitte enam ~50×) — see on
Ruuteri, mitte meie kood, ja skaleerub. Päris serveris (rohkem tuumi) väiksem.

## Mis on veel vaja / otsustada

- Haru üle vaadata ja `dev`-i merge'ida (20 commiti, kolm loogilist teemat — võib
  eraldi PR-ideks jagada).
- Otsustada, kas alati broadcast'ida (ka lokaalse vaste korral) — praegu lokaalne vaste
  lõpetab voo; reg 2020/1056 tõlgendus võib nõuda kõigi väravate pärimist.
- Koormustesti metoodika kokku leppida (k6 ramp + think-time, kihtide kaupa, seed mis
  tabab) — vt `2-notes-for-pikker.md`.

## Detailid

Täisanalüüs mõõtmiste, `EXPLAIN`-plaanide ja kordustootmise skriptidega:
`docs/askend_performance/analysis.md` (harul). ADR-009 ja ADR-010
`docs/architecture/decisions/`.
