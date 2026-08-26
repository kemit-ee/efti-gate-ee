# Known Issues

Teadaolevate probleemide ja piirangute register. Iga kirje sisaldab staatuse, mõjutatud komponendi, lühikirjelduse ja järgmise sammu.

**Staatused:**
- 🔴 **Open** — lahendamata, aktiivselt mõjutab
- 🟡 **Mitigated** — töötab, kuid piiranguga (kalkuleeritud risk)
- 🟢 **Resolved** — lahendatud (versioon + kuupäev)

---

## KI-001 · PostgreSQL JDBC driver ceiling (RESQL + TIM)

**Staatus:** � Resolved (2026-08-12)
**Mõjutatud komponendid:** ~~`resql-efti`, `tim`~~

### Kirjeldus

Algne probleem puudutas LJVIS-aegset stacki: `ghcr.io/buerokratt/resql:v1.3.4` ja `tim` kasutasid JDBC draiverit 42.3.9 (toetas kuni PostgreSQL 15), mis seadis piirangu PostgreSQL 16+ kasutamisele.

### Lahendus

Stackist on eemaldatud mõlemad Java-komponendid:

- **`resql`** on asendatud `turnerrainer/resql:0.1.0-alpha.1`-ga — Rust-põhine implementatsioon, mis kasutab native PostgreSQL wire-protokolli. JDBC draiverit ei kasuta; PostgreSQL versiooni piirangut pole.
- **`tim`** — komponenti pole efti-gate-ee stackis kasutusel.

PostgreSQL 18 kasutamine on alates sellest muutusest täielikult toetatud.

---

## KI-002 · Liquibase 5.0.x — changelog file not found

**Staatus:** 🟡 Mitigated
**Mõjutatud komponendid:** `liquibase`

### Kirjeldus

Liquibase Docker image versioonid `5.0.3` (ja tõenäoliselt ka teised 5.0.x väljalasked) ei käivitu ning annavad veateate:

```
ChangeLogParseException: /liquibase/changelog.yaml does not exist
ChangeLogParseException: /efti/changelog.yaml does not exist
```

Viga ilmub ka siis, kui `db.changelog-master.yaml` on olemas nii image'i `COPY` käsuga lisatud kui ka konteinerisse bind-mount'itud.

### Põhjus

Liquibase 5.0.x Docker image kasutab `/liquibase/` kausta oma installi/runtime teena. 5.0.x changelog resource loader ei suuda changelog faili õigesti lahendida ei `/liquibase/` ega ka mitte ühestki teisest konteineri teest. Ka `liquibase.properties` failis määratud `searchPath` omadust ei rakendata enne changelog faili otsingut, seega ei aita ei absoluutsed teed ega ka kohandatud kaustad.

### Lahendus (workaround)

Kasuta viimast projektiga ühilduvat Liquibase 4.x versiooni. Hetkel kinnitatud image on:

```dockerfile
FROM liquibase/liquibase:4.29.2
```

Liquibase 4.29.2-ga töötab järgmine seadistus:
- `docker/liquibase/Dockerfile`: `COPY DSL/Liquibase/ /liquibase/`
- `compose.yml` ja `compose.ci.yml`: bind-mount `./DSL/Liquibase/` → `/liquibase/changelog`
- `DSL/Liquibase/liquibase.properties`: `changeLogFile: db.changelog-master.yaml`, `searchPath: /liquibase/changelog`
- Liquibase käsk: `--defaultsFile=/liquibase/changelog/liquibase.properties update`

### Järgmine samm

Hinda Liquibase 5.x ühilduvust uuesti siis, kui saadaval on uuem 5.x väljalase või kui Liquibase dokumenteerib `searchPath`/`changelogFile` õige seadistamise Docker image'is.

### Viited

- `docker/liquibase/Dockerfile` — Liquibase versioon
- `compose.yml`, `compose.ci.yml` — compose seadistus
- `DSL/Liquibase/liquibase.properties` — Liquibase konfiguratsioon

---

## KI-003 · ResQL JSON parameter type mapping muutused alpha versioonides

**Staatus:** 🟡 Mitigated (2026-08-13)
**Mõjutatud komponendid:** `resql-efti`

### Alpha.1 — JSON boolean binary serialization bug

ResQL 0.1.0-alpha.1 serialiseerib JSON request body boolean väljad PostgreSQL prepared statement parameetritena binary protokollis (`\u0001`/`\u0000`) asemel tekstina. PostgreSQL COALESCE-lausetes tekitab see vea:

```
invalid input syntax for type boolean: "\u0001"
COALESCE types text and boolean cannot be matched
```

**Workaround (alpha.1):** eemalda boolean veerud INSERT column listist (kasuta DB DEFAULT `true`) või kasuta `COALESCE(:param::text, 'false')::boolean` mustrit.

### Alpha.2 — JSON massiivid saadetakse `text[]`-na

ResQL 0.1.0-alpha.2 konverteerib JSON request body massiivid (`["EU01","EU02"]`) PostgreSQL natiivseks `text[]` tüübiks. Alpha.1-s saadeti need JSON tekstina. See tähendab, et `::jsonb` cast massiivi parameetritele ebaõnnestub:

```
cannot cast type text[] to jsonb
```

Mõjutab SQL lauseid, kus JSON array parameetrit castiti `::jsonb`-ks:
```sql
-- KATKINE alpha.2-s:
ARRAY(SELECT jsonb_array_elements_text(:subsets::jsonb))
-- ÕIGE alpha.2-s (kasuta otse, alpha.2 saadab juba text[]):
:subsets
```

JSON objekt parameetrid (nt `roles jsonb`) töötavad `::jsonb` cast-iga edasi.

### Lahendus (alpha.2)

Eemaldatud `ARRAY(SELECT jsonb_array_elements_text(... ::jsonb))` konstruktsioon — kasuta `:subsets` otse, kuna alpha.2 saadab JSON massiivi juba PostgreSQL `text[]`-na.

### Mõjutatud failid

- `DSL/Resql/efti/POST/insert_gate.sql` — boolean workaround (alpha.1)
- `DSL/Resql/efti/POST/update_gate.sql` — boolean workaround (alpha.1)
- `DSL/Resql/efti/POST/insert_platform.sql` — boolean workaround (alpha.1)
- `DSL/Resql/efti/POST/update_platform.sql` — boolean workaround (alpha.1)
- `DSL/Resql/efti/POST/insert_authority.sql` — boolean + array workaround (alpha.1 → alpha.2)
- `DSL/Resql/efti/POST/update_authority.sql` — boolean + array workaround (alpha.1 → alpha.2)
- `DSL/Resql/efti/POST/insert_user.sql` — array workaround (alpha.2)
- `DSL/Resql/efti/POST/update_user.sql` — array workaround (alpha.2)

### Alpha.4 — kõik SQL-failid peavad algama `/* */` deklaratsioonikommentaariga

ResQL 0.1.0-alpha.4 nõuab, et iga SQL-fail algaks `/* ... */` blokk-kommentaariga, mis sisaldab vähemalt `description` ja `params` välju. Ilma selleta keeldub ResQL käivitumast:

```
Invalid declaration in './sql/efti/POST/check_tara_sub_exists.sql': file must open with a /* … */ block-comment declaration
```

Samuti valideeritakse parameetri tüübid päringus. `type: integer` kasutamine `limit`/`offset` parameetritele koos `::int` cast-iga SQL-is põhjustab vea `invalid byte sequence for encoding "UTF8": 0x00`. **Workaround:** deklareeri `limit`/`offset` tüübiga `type: string` — SQL-i `COALESCE(:limit::int, 20)` teeb type cast ise.

Minimaalne deklaratsiooni formaat:
```sql
/*
description: lühikirjeldus
params:
  paramName:
    type: string   # string | integer | object
*/
SELECT ...
```

### Lahendus (alpha.4)

Lisatud `/* */` deklaratsioonid kõigile 36 SQL-failile. Pakett `docker/resql/Dockerfile` uuendatud `turnerrainer/resql:0.1.0-alpha.2` → `turnerrainer/resql:alpha`.

### Järgmine samm

Jälgi ResQL release'e. Hetkel kasuta `docker/resql/Dockerfile` versiooni `turnerrainer/resql:alpha` (praegu 0.1.0-alpha.4).

---

## KI-005 · Ruuter — XML tagastamine mähituna JSON jutumärkidesse

**Staatus:** 🟢 Resolved (Ruuter 0.9.0-rc.1, 2026-08-26)
**Mõjutatud komponendid:** `DSL/Ruuter/efti/POST/api/v1/consignments-xml.yml`, `DSL/Ruuter/efti/POST/api/v1/consignments/search-xml.yml`

### Kirjeldus

Ruuter versioonides enne 0.9.0-rc.1: kui `return:` samm tagastas stringi muutuja (sh XML-teksti), serialiseeris Ruuter selle JSON-stringina — klient sai `"<xml>...</xml>"` (jutumärkidega) asemel puhta `<xml>...</xml>`.

```yaml
# Enne 0.9.0-rc.1 — tagastas: "<FTI021...>...</FTI021...>" (jutumärkidega!)
respond:
  return: ${xml_response.response.body.xml}
  headers:
    Content-Type: "text/xml"
  wrapper: false
```

Workaround oli mähkida XML `RuuterXmlWrapper(val xml: String)` JSON-objekti (`{"xml": "..."}`) xml-mapper'is ning võtta `.xml` väli Ruuteri DSL-is — kuid see ei lahendanud jutumärkide probleemi, ainult peitis selle.

### Lahendus

Ruuter 0.9.0-rc.1 tagastab `return: ${xml_string}` koos `wrapper: false` ja `Content-Type: text/xml` nüüd puhta XML-i ilma jutumärkideta. `RuuterXmlWrapper` muster töötab edasi — lihtsalt `.xml` väli jõuab nüüd kliendini õigesti.

```yaml
# 0.9.0-rc.1+ — tagastab: <FTI021...>...</FTI021...> (korrektne)
respond:
  return: ${xml_response.response.body.xml}
  headers:
    Content-Type: "text/xml"
  wrapper: false
```

### Viited

- `docker/ruuter/Dockerfile` — `FROM turnerrainer/ruuter:rc` (praegu 0.9.0-rc.1)

---

## KI-004 · Users API — auth-sõltuvad reeglid rakendamata

**Staatus:** 🟡 Mitigated (2026-08-12)
**Mõjutatud komponendid:** `DSL/Ruuter/efti/POST/api/v1/users.yml`, `DSL/Ruuter/efti/DELETE/api/v1/users/delete.yml`

Kolm reeglit spetsifikatsioonist on rakendamata, kuna vajavad JWT autentimiskonteksti (kõik guard-failid on hetkel `allow-all`). Täpsem kirjeldus ja järgmised sammud: [`docs/planning/user_api_known_restrictions.md`](user_api_known_restrictions.md).
