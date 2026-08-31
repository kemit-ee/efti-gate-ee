# ADR-005: UI-liidese ja masinliidese eraldamine kaheks Ruuteriks

**Otsus (Sten Viljus, 31.08.2026):**

## Otsus

eFTI värava Ruuteri marsruudid jagatakse kahe instantsi vahel turvamudeli järgi:

| Ruuter | Port | Projekt | Sisu | Autentimine |
|---|---|---|---|---|
| `efti` | 8086 | `efti` | Admin UI API + `auth/*` | admin/kasutaja JWT (TIM/TARA) |
| `m2m` | 8087 | `m2m` | Partnervärava eDelivery, Authority API, Platform API, X-Road | vt allpool (alamkataloogi kaupa) |

Varasem `ruuter-xroad` nimetatakse ümber `ruuter-m2m`-iks — see instants ei ole
ainult X-Road, vaid kogu masin-masin liides.

## m2m guardid (alamkataloogi kaupa)

```
m2m/POST/xroad/       X-Road-Client päis + authorities registri kontroll
m2m/POST/edelivery/   partnervärava AS4 — ainult kohalikust edelivery teenusest (võrgu-usaldus)
m2m/POST/platform/    X-Api-Key päis -> platforms registri räsikontroll (ADR-004)
m2m/POST/authority/   X-Road-Client (kui olemas) või eDelivery
m2m/GET/authority/    sama mis POST/authority/
```

## Põhjus

- `POST/api/v1/.guard` `efti` Ruuteris oli sunnitud olema no-op, sest üks kataloog
  segas kolme turvamudelit (admin UI, authority, platform/G2G). Admin-kontroll oli
  kopeeritud iga admin-endpointi DSL-i algusesse (`check-admin-authority` väljakutse).
- Pärast jagamist on iga kataloogi guard ühtne ja tähenduslik: `efti/POST` = admin,
  `m2m/POST/<domeen>` = selle domeeni kredentsiaal. Uut marsruuti ei saa kogemata
  valveta jätta.
- Kaks instantsi saab eraldi võrku/ingressi paigutada, eraldi skaleerida ja
  logireegleid rakendada. UI-liides ei ole partnerväravatele avatud ja vastupidi.

## Marsruutide teisaldus

| Vana (`efti`) | Uus (`m2m`) |
|---|---|
| `POST /efti/api/v1/consignments-xml` | `POST /m2m/edelivery/v1/consignments-xml` |
| `POST /efti/api/v1/consignments/search-xml` | `POST /m2m/edelivery/v1/consignments-search-xml` |
| `POST /efti/api/v1/dataset-xml` | `POST /m2m/edelivery/v1/dataset-xml` |
| `POST /efti/api/v1/follow-up-xml` | `POST /m2m/edelivery/v1/follow-up-xml` |
| `POST /efti/api/v1/consignments` | `POST /m2m/platform/v1/consignments` |
| `POST /efti/api/v1/consignments/search` | `POST /m2m/platform/v1/consignments/search` |
| `POST /efti/api/v1/ping` | `POST /m2m/platform/v1/ping` |
| `POST /efti/api/v1/dataset` | `POST /m2m/authority/v1/dataset` |
| `POST /efti/api/v1/follow-up` | `POST /m2m/authority/v1/follow-up` |
| `POST /efti/api/v1/authority/search` | `POST /m2m/authority/v1/search` |
| `POST /efti/api/v1/authority/follow-up` | `POST /m2m/authority/v1/follow-up-notify` |
| `GET /efti/api/v1/authority/dataset` | `GET /m2m/authority/v1/dataset` |
| `GET /efti/api/v1/follow-up` | `GET /m2m/authority/v1/follow-up` |
| `POST /xroad/v1/echo` | `POST /m2m/xroad/v1/echo` |

`GET /efti/api/v1/consignments` jääb `efti`-sse (admin nimekiri).
`gates/:id/ping` ja `platforms/:id/ping` jäävad `efti`-sse admin-guardi alla
(UI "testi ühendust"); automaatse pingi tee ops-kredentsiaaliga `m2m`-is on eraldi töö.

## Rakendus

Branch `refactor/split-m2m-ruuter`. Vt `docs/planning/m2m-ruuter-split.md`.
