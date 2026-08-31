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

`platforms` on append-only, seega iga muudatus kannab need veerud uude ritta edasi
(`COALESCE(:uus, latest.<veerg>)`).

## Kontroll (m2m Ruuter, `m2m/POST/platform/.guard`)

1. `X-Api-Key` päis puudub → **401**
2. räsi ei leia ühtegi aktiivset (`status != 'DELETED'`) platvormi → **401**
3. räsi leiab >1 aktiivse platvormi (registri viga) → **403** `forbidden-multi-platform`
4. täpselt üks → päring lubatud

Räsi arvutatakse ja võrreldakse SQL-i pool (`get_platform_by_api_key`), võti ei
jõua kunagi logisse. Logiredaktsioon eemaldab `X-Api-Key` päise.

## Kasutajaliides

Platvormi vaates on nupp **"Genereeri API võti"**. Vajutamisel luuakse uus võti,
mida näidatakse eraldi modaalis koos **"Kopeeri"** nupuga. Modaali sulgemisel pole
võtit enam võimalik näha. Loendis ja detailvaates on näha ainult
`api_key_generated_at` ja `api_key_hint`.

## Põhjus

- Platvormiliides on masinliiklus ilma kasutajata — TARA/JWT ei sobi.
- mTLS oleks nõudnud igale platvormile Member-State X.509 serti ja pöördproksi
  konfiguratsiooni; API võti on lihtsam kasutusele võtta arendus- ja
  pilootfaasis. mTLS jääb võimalikuks tugevduseks hiljem.
- Räsina hoiustus tähendab, et andmebaasi leke ei avalda platvormide kredentsiaale.

## Rakendatav changeset

`DSL/Liquibase/changelog/20260827-platform-api-key.sql`
