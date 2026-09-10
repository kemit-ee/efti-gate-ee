# Jõudlusparandused — Askendi poolt

*consignments search (`authority/search`) koormustestide järelkontroll. 10.09.2026.*

## Kokkuvõte

consignments search'i (`authority/search`) koormustestid on üle valideeritud. Osa
esialgseid tulemusi kordusmõõtmisel **ei kordunud** — testi­seadistus ja mõõtmis­metoodika
mõjutasid numbreid. Samas tuli valideerimisel välja **üks tegelik jõudlusküsimus**
andmebaasipäringus, mis on nüüd lahendatud. Lisaks tegime paar väiksemat parandust.

## Mis üle valideeriti

- Kordusmõõtmine tehti korrigeeritud testandmete ja -konfiguratsiooniga.
- Mõõtmine tehti **kihtide kaupa** (andmebaas eraldi; andmebaas + päringukiht eraldi; terve
  marsruut läbi rakenduse eraldi), et tulemus oleks üheselt tõlgendatav.
- Lisaks `ApacheBench`-i burst-testile tehti realistlik profiil `k6`-ga: järk-järgult
  kasvav kasutajate arv ja mõtlemispaus päringute vahel — see näitab latentsi jaotust
  (p50/p90/p95/p99) tegeliku kasutuse all, mitte küllastuspunktis.

## Tehtud muudatused

| Muudatus | Põhjus | Mõju |
|---|---|---|
| **Andmebaasipäringu optimeerimine.** Otsing resolvis konsignatsiooni viimase versiooni kogu tabelit läbi sortides. Viisime indeksipõhisele lähenemisele — sama tulemus, sama andmemudel (append-only). | Suure andmemahu juures kasvas päringu aeg lineaarselt tabeli suurusega. | Päring 1 000 000 kirje juures: **~23 s → alla 1 ms** |
| **Otsinguvoo muutmine mitte-blokeerivaks.** Kui lokaalne osa tulemust ei andnud, võis päring oodata kuni 65 s teiste väravate vastust ja siis ebaõnnestuda. Nüüd tagastatakse lokaalne tulemus kohe; teiste väravate omad kogutakse eraldi pärimisega. | Kliendi jaoks prognoosimatu vastuseaeg ja aeg-ajalt viga. | Vastus alati kiire; ei esine 65 s ooteid |
| **Platvormi (DSL-mootori) versiooniuuendus** ja väiksem sisemine korrastus. | Töökindlus; ühe varem raporteeritud vea parandus. | — |
| **Ressursikonfiguratsioon** (protsessoripiirangud, andmebaasi ühenduste pool) realistlikule tasemele. | Testi­keskkond oli seatud alla sihttaseme. | Läbilaskevõime ja skaleeruvus |

Kõik muudatused läbisid olemasoleva automaattestide komplekti (208 stsenaariumit) ilma
regressioonideta.

## Tulemused pärast muudatusi

Realistlik profiil (k6, kasvav koormus kuni 100 samaaegse kasutajani, mõtlemispaus 0,5–1,5 s,
~30 000 päringut, 0 viga):

| Näitaja | 1 kirje | 1 000 000 kirjet |
|---|---|---|
| mediaan (p50) | ~6 ms | ~7 ms |
| p95 | ~16 ms | ~21 ms |
| p99 | ~30 ms | ~57 ms |

Sama andmebaasi otse pärides p50 ~2 ms, p99 ~40 ms. Andmemahu kasv 1 kirjelt 1 000 000-le
tõstab p99 ~30 → ~57 ms (indeksi- ja vahemälu­efekt) — **andmebaas ei ole kitsaskoht üheski
mahus**.

`ApacheBench`-i burst-test (250 samaaegset päringut korraga, ilma pausita) andis oluliselt
kõrgema p99 — see mõõdab järjekorra­ootust küllastuspunktis, mitte latentsi realistliku
koormuse all. Mõlemad numbrid on dokumenteeritud.

## Piirangud

- Mõõtmised on tehtud arenduskeskkonnas, mitte sihtriistvaral. Sihtserveris (rohkem
  protsessorituumasid, väiksem koormus­konkurents) on absoluutnumbrid tõenäoliselt paremad;
  oluline on suhe ja skaleeruvus.
- Alles­jääv piirang: rakenduse DSL-mootor lisab iga päringu kohta mõni millisekund
  töötlusaega. See on platvormi omadus ja skaleerub konkurentsiga.
- Lahtine otsus: kas identifikaatoripäring, mis leidis lokaalse vaste, peaks ikkagi kõiki
  teisi riiklikke väravaid pärima (Reg 2020/1056 tõlgendus).
