# eFTI värava kasutusjuhend

See juhend kirjeldab eFTI värava haldusliidese igapäevast kasutamist. Haldusliideses saab hallata kasutajaid ja saadetisi ning vaadata teiste riikide väravaid, eFTI platvorme ja pädevaid asutusi.

> **Muudatus (oktoober 2026):** väravate, platvormide ja pädevate asutuste registrid on haldusliideses **ainult lugemiseks**. Neid hallatakse värava lähtekoodi repositooriumis (`registry/`) ning muudatus jõuab väravasse uue versiooni paigaldamisega ([ADR-011](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/architecture/decisions/011-registries-as-signed-config.md)). Lisamise, muutmise, kustutamise, pingimise ja API võtme genereerimise nupud on eemaldatud.

Juhend vastab GitHubi `dev` haru kasutajaliidesele. Tehniline dokumentatsioon ja lähtekood on [eFTI Gate’i GitHubi repositooriumis](https://github.com/kemit-ee/efti-gate-ee).

## Süsteemi ülesehitus

Ruuter töötab ühe runtime’ina, kuid iga Ruuteri projekt on eraldi URL-ruumi, sisendlepingu ja autentimisreeglitega loogiline teenus. Kotlin-teenused tegelevad XML-i teisendamise, väravatevahelise sõnumivahetuse, otsingu hargnemise ja asünkroonsete vastustega. ReSql eraldab API-kihi SQL-ist ning PostgreSQL hoiab kasutajate ja saadetiste andmeid. Väravate, platvormide ja asutuste registrit serveerib eraldi sisemine `registry` teenus.

![eFTI värava komponendid ja nendevahelised seosed](images/14-system-architecture.png)

![eFTI värava haldusliidese avavaade](images/01-gates-overview.png)

## Kellele juhend on mõeldud?

Juhend on mõeldud eFTI värava administraatorile. Kõigil süsteemi lisatud ja edukalt autenditud kasutajatel on sama haldusõigus; kasutajatele eraldi rolle ei määrata.

## Põhimõisted

| Mõiste | Tähendus |
|---|---|
| Värav | Euroopa Liidu liikmesriigi eFTI värav, millega Eesti värav eDelivery kaudu suhtleb. |
| Platvorm | Sertifitseeritud eFTI platvorm, mis saadab väravale saadetiste identifikaatoreid ja väljastab andmekogumeid. |
| Pädev asutus | Asutus, kes võib X-Roadi kaudu eFTI andmeid pärida. Asutusele määratakse lubatud andmete alamhulgad. |
| Kasutaja | TARA kaudu autentiv administraator, kes on väravas isikukoodi alusel registreeritud. |
| Register | Väravate, platvormide ja asutuste andmed repositooriumi kaustas `registry/`; haldusliideses ainult loetav. |
| Saadetis | Platvormilt väravasse üles laaditud eFTI saadetise kirje koos identifikaatorite ja XML-andmetega. |

## Haldusliidese jaotised

Ülamenüüs on viis põhivaadet: **Väravad**, **Platvormid**, **Pädevad asutused**, **Kasutajad** ja **Saadetised**. Paremas ülanurgas saab vahetada keelt ning kasutajamenüüst välja logida.
