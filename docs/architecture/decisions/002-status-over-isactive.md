# ADR-002: `is_active` asendamine `status` väljaga (gates, platforms)

**Otsus (Sten Viljus, Anton Keks, 14.08.2026):**

## Otsus

`is_active` (BOOLEAN) eemaldatakse `gates` ja `platforms` tabelitest.
Kanooniline seisundi väli on `status` (`gate_status` ENUM).

## ENUM väärtused

| Väärtus | Tähendus |
|---|---|
| `ONLINE` | Aktiivne ja kättesaadav (viimane ping õnnestus) |
| `OFFLINE` | Kättesaadamatu (ping ebaõnnestus või pole kunagi pingitud) |
| `DISABLED` | Halduslikult välja lülitatud; nähtav loendites, kuid ei osale päringutes |
| `DELETED` | Pehme kustutus operaatori poolt; rida säilib andmebaasis auditeerimiseks |

## Käitumisreeglid

- **Pehme kustutus** = uus rida `status = 'DELETED'` (append-only loogika kohaselt)
- **Loendi filter** = `WHERE status != 'DELETED'` (nähtavad: ONLINE, OFFLINE, DISABLED)
- **Üksiku kirje päring** (`GET /gates?gateId=...`) tagastab ka DELETED kirje (nähtav auditiks)
- `authorities` ja `users` tabelid jäävad praegu `is_active`-ga (eraldi otsus vajalik)

## Põhjus

`is_active` ja `status` olid kaks kattuvat seisundi välja samal kirjel, mis tekitas ebamäärasust:
- Kumb on autoriteetne?
- Kas `is_active=FALSE, status=ONLINE` on lubatud?
- Mida tähendab `is_active=TRUE, status=DISABLED`?

ENUM on kanooniline, enesedokumenteeriv ja laiendatav ilma skeemimuutuseta.

## Rakendatav changeset

`DSL/Liquibase/changelogs/002-status-refactor.sql`
