# ADR-004: Platvormide autentimine API võtmega (`X-Api-Key`)

**Otsus (Rainer Türner, Sten Viljus, Anton Keks, 25.08.2026):**

## Otsus

eFTI platvormid autendivad end väravale **API võtmega**, mis saadetakse HTTP
päises `X-Api-Key`. Iga platvorm saab ühe võtme.

Võtit **ei hoita kunagi avatekstina** — andmebaasi salvestatakse ainult selle
SHA-256 räsi (`platforms.api_key_hash`). Avatekstina näidatakse võtit täpselt üks
kord, genereerimise hetkel.

## Skeem

| Veerg | Tüüp | Sisu |
|---|---|---|
| `api_key_hash` | `BYTEA` | `digest(<võti>, 'sha256')` |
| `api_key_hint` | `TEXT` | räsi heksa esimesed 8 märki — pöördumatu silt kasutajaliidesele |
| `api_key_generated_at` | `TIMESTAMPTZ` | millal kehtiv võti genereeriti |

`platforms` on append-only — iga muudatus (`update_platform`, ping, soft-delete)
kannab need veerud uude ritta edasi. `get_platforms` / `get_platform_by_id`
tagastavad ainult `api_key_hint`, `api_key_generated_at` ja `has_api_key`, mitte
räsi ega võtit.

## Kontroll (`DSL/Ruuter/platforms/POST/v1/consignments.guard`)

1. `X-Api-Key` päis puudub → **401**
2. `digest(võti,'sha256')` ei leia ühtegi aktiivset (`status != 'DELETED'`) platvormi → **401**
3. leiab >1 aktiivse platvormi (registri viga) → **403** `forbidden-multi-platform`
4. täpselt üks → päring lubatud

Räsi arvutatakse ja võrreldakse SQL-i pool (`get_platform_by_api_key`), võti ei
jõua kunagi logisse. Logiredaktsioon eemaldab `X-Api-Key` päise.

## Genereerimine

`POST /admin/v1/platforms/api-key/:id` (admin). Lisab append-only `platforms` rea
uue juhusliku 24-baidise võtmega, salvestab räsi + hint + `NOW()`, tagastab
avatekstina **üks kord**:

```json
{ "id": "...", "apiKey": "9f8e…c1b2", "apiKeyHint": "3a1f9c02", "apiKeyGeneratedAt": "..." }
```

## Kasutajaliides

Platvormide loendis on nupp **"Genereeri API võti"**. Vajutamisel luuakse uus
võti, mida näidatakse eraldi modaalis koos **"Kopeeri"** nupuga. Modaali
sulgemisel pole võtit enam võimalik näha. Loendis on näha ainult
`apiKeyGeneratedAt` ja `apiKeyHint`.

## Põhjus

- Platvormiliides on masinliiklus ilma kasutajata — TARA/JWT ei sobi.
- mTLS oleks nõudnud igale platvormile Member-State X.509 serti ja pöördproksi
  konfiguratsiooni; API võti on lihtsam kasutusele võtta arendus- ja
  pilootfaasis. mTLS jääb võimalikuks tugevduseks hiljem.
- Räsina hoiustus tähendab, et andmebaasi leke ei avalda platvormide kredentsiaale.

## Rakendatav changeset

`DSL/Liquibase/changelog/20260902-platform-api-key.sql`
