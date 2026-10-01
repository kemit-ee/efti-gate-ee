# ADR-011: Registrid (gate'id, platvormid, asutused) allkirjastatud konfiguratsioonina, release'iga jõustuvad

**Staatus: OTSUSTAMISEL** (arutelu: Sten Viljus, Rainer Türner). See on mustand, mitte vastu võetud otsus.

Seotud issue'd: kemit-ee/efti-gate-ee#187 (A7), kemit-ee/efti-gate-ee#190 (A7-IMPL).

## Probleem

Gate'ide, platvormide ja volitatud asutuste register on praegu muudetav admin-REST-iga
(`/admin/v1/{gates,platforms,authorities}`) ühe admin-JWT-ga. Register on aga õiguslik/lepinguline
artefakt: liitumine on ametlik sündmus, mitte runtime'i mutatsioon. Lubamatu muudatus või vigane
sisend kahjustab otse:

- gate'i URL-i vahetus → signeeritud AS4 sõnum läheb ründaja endpoint'i (Chain-C);
- vigane gate'i rida → edelivery ei käivitu (F-EDEL-BOOTFAIL-1);
- vigane PEM → usaldusankru mürgitamine (F-EDEL-19);
- tühja `subsets`-iga asutus → subset-kontrollist möödumine (F-SQL-1);
- Class-2 vead kõigil CRUD-radadel (topeltkodeering, tüübivead, pikkuspiirangud jne).

Tabelid on append-only ja kehtib "viimane rida võidab". Igaüks, kellel on registritabelisse
INSERT-õigus, saab seega registri üle kirjutada. Ainult route'i eemaldamisest ei piisa, kui
`app` rollil see õigus alles jääb.

## Otsus (ettepanek)

1. **Allikas.** Register elab repos kataloogis `registry/`: üks YAML iga gate'i, platvormi ja
   asutuse kohta, sertifikaadid eraldi PEM-failidena, platvormi API-võtmest ainult hash.
   Kataloog ei asu Ruuteri DSL-kataloogis, sest iga selle esimese taseme kataloog on Ruuteri
   projekt ja failid loetaks marsruutideks.
2. **Allkiri.** Release'i käigus allkirjastatakse manifest (kõigi failide hashid). Avalik võti
   on image'is.
3. **Laadur.** Init-konteiner kontrollib allkirja, valideerib skeemi (URL-skeem, PEM parsimine,
   mittetühi `subsets`), kirjutab andmed append-only tabelitesse eraldi DB rolliga ja lõpetab.
   Ebaõnnestumisel jääb ta veaga lõppema ja `depends_on: service_completed_successfully` ei lase
   teenuseid käivitada (fail-closed).
4. **Õigused.** `app` roll kaotab registritabelitest INSERT-õiguse.
5. **CRUD eemaldamine.** Admin POST/PUT/DELETE gate'idele, platvormidele ja asutustele, koos
   alamroutedega (`api-key`, ping, revoke-token), eemaldatakse. GET-id jäävad ja Admin GUI
   muutub read-only vaateks, mis loeb samast allikast mida runtime.
6. **Kustutamine.** Kataloogist kadunud kirjed märgitakse DB-s tombstone'iga (append-only).
7. **Lugejad ei muutu.** Guardid (`platforms/`, `xroad/`), edelivery `ResqlClient`,
   multiplexer ja SQL loevad samadest tabelitest.
8. **Ruuteri konstandid.** Konstantides on ainult manifesti hash/versioon drift-tuvastuseks,
   mitte andmeallikas.
9. **Kasutajate CRUD** jääb REST-iks (kasutajad liituvad ja lahkuvad regulaarselt, see ei ole
   lepinguline artefakt).

## Põhjus

- Muudatus jõustub ainult release'iga, mis jätab jälje (git-ajalugu, ülevaatus, allkiri,
  deploy-logi). See on tahtlik omadus, mitte piirang.
- Kirjutamisõiguse äravõtmine (mitte ainult route'i kustutamine) sulgeb tee ka SQL-i ja
  Ruuteri kaudu.
- Lugejad jäävad samaks, seega koodimuudatus on väike (enamasti kustutamine).

## Tagajärjed

- Sulgeb: Chain-C, F-EDEL-BOOTFAIL-1, F-EDEL-19, F-SQL-1 ja Class-2 vead nende CRUD-radadel.
- Ei sulge (issue'i enda piirang): F-SQL-2/3 (platvormi API-võtme ajastus/vihje, kuni API-võtme
  autentimine ei kolinud ka konfiguratsiooni või mTLS-i), js-error, dev-login (Chain-A samm 1),
  F-EDEL-HANDLER-1.
- Registri muutmine käib PR-i, ülevaatuse, allkirjastamise ja deploy kaudu. Hädaolukorraks
  (nt lekkinud platvormi võti) on vaja kiiret hotfix-release'i teed, muidu hakatakse registrit
  käsitsi DB-s parandama.
- Muudetavad failid: admin DSL-marsruudid (~1100 rida), vastav SQL, `tests/admin/{gates,platforms,authorities}.http`
  (asendatakse negatiivsetega), UI vormid `code/ui/src/pages/admin/{gates,platforms,authorities}`.
- Hinnang: umbes 4–7 arendaja-päeva, sõltuvalt signeerimisskeemi otsusest.

## Kaalutud alternatiivid

- **Kirjutamine Ruuteri internal route'i kaudu `app` rolliga.** Jätab kirjutusraja alles;
  token on eluks ajaks kättesaadav. Kui siiski, siis eraldi ReSql datasource eraldi rolliga
  (nagu `archive`) ja route'i guard nõuab internal tokenit.
- **Kotlin-teenused loevad faile otse.** Annab kaks andmeallikat, sest guardid vajavad DB-d.
- **Register Ruuteri konstantides.** Konstandid on lamedad `KEY=value`, nimekirjade jaoks
  tuleks guardid ümber kirjutada, mis on just see koodimuudatus, mida tahame vältida.

## Avatud küsimused

1. Signeerimisskeem ja võtmehoid (issue viitab F-GC-3 disainile, mida selles repos ei ole).
2. API-võtmete väljastamine ja rotatsioon ilma runtime-endpoint'ita.
3. Manifesti kohaletoomine keskkondadesse (image või mount'itud Secret).
4. Hotfix-release'i protsess.

## Seotud

- [ADR-002](002-status-over-isactive.md), [ADR-004](004-platform-api-key.md),
  [ADR-006](006-xroad-identity-and-subsets.md)
- kemit-ee/efti-gate-ee#187, kemit-ee/efti-gate-ee#190
