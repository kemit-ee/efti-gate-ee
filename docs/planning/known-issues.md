# Known Issues

Teadaolevate probleemide ja piirangute register. Iga kirje sisaldab staatuse, mõjutatud komponendi, lühikirjelduse ja järgmise sammu.

**Staatused:**
- 🔴 **Open** — lahendamata, aktiivselt mõjutab
- 🟡 **Mitigated** — töötab, kuid piiranguga (kalkuleeritud risk)
- 🟢 **Resolved** — lahendatud (versioon + kuupäev)

---

## KI-001 · PostgreSQL JDBC driver ceiling (RESQL + TIM)

**Staatus:** 🟡 Mitigated  
**Mõjutatud komponendid:** `resql-efti`, `tim`

### Kirjeldus

`resql-efti` (`ghcr.io/buerokratt/resql:v1.3.4`) ja `tim` (`ghcr.io/buerokratt/tim:pre-apha-2.7.1`) kasutavad mõlemad sisseehitatud PostgreSQL JDBC draiverit **42.3.9**, mis ametlikult toetab PostgreSQL serverit kuni versioonini **15**.

Süsteem käitatakse hetkel **PostgreSQL 17-ga** — see on testitud kalkuleeritud risk. JDBC wire-protokoll on tagasiühilduv ning kõik rakenduses kasutatavad operatsioonid (INSERT, SELECT, UPDATE, DELETE, pgcrypto funktsioonid) toimivad korrektselt.

| Komponent | JDBC driver | Ametlik PG tugi | Testitud |
|-----------|-------------|-----------------|---------|
| `resql-efti` `v1.3.4` | `42.3.9` | ≤ PostgreSQL 15 | PostgreSQL 17 ✓ |
| `tim` `pre-apha-2.7.1` | `42.3.9` | ≤ PostgreSQL 15 | PostgreSQL 17 ✓ |
| `liquibase` `4.29.2` | `42.7.11` | ≤ PostgreSQL 15 | PostgreSQL 17 ✓ |

### Piirang

**PostgreSQL 18 või uuemat ei tohi kasutada** seni, kuni RESQL ja TIM ei ole uuendatud versioonile, mis kasutab JDBC draiverit ≥ 42.6.

### Järgmine samm

1. Jälgi Buerostack upstream repositooriumites (`resql`, `tim`) uuendusi.
2. Kontrolli uue versiooni bundled JDBC draiveri versiooni.
3. Kui JDBC ≥ 42.6 → saab minna PostgreSQL 18 peale.

## KI-002 · Liquibase 5.0.x — changelog file not found

### Kirjeldus

Liquibase Docker image versioonid `5.0.3` (ja tõenäoliselt ka teised 5.0.x väljalasked) ei käivitu ning annavad veateate:

```
ChangeLogParseException: /liquibase/changelog.yaml does not exist
ChangeLogParseException: /efti/changelog.yaml does not exist
```

Viga ilmub ka siis, kui `changelog.yaml` on olemas nii image'i `COPY` käsuga lisatud kui ka konteinerisse bind-mount'itud.

### Põhjus

Liquibase 5.0.x Docker image kasutab `/liquibase/` kausta oma installi/runtime teena. 5.0.x changelog resource loader ei suuda `changelog.yaml` õigesti lahendida ei `/liquibase/` ega ka mitte ühestki teisest konteineri teest. Ka `liquibase.properties` failis määratud `searchPath` omadust ei rakendata enne changelog faili otsingut, seega ei aita ei absoluutsed teed ega ka kohandatud kaustad (nt `/efti/`).

### Lahendus (workaround)

Kasuta viimast projektiga ühilduvat Liquibase 4.x versiooni. Hetkel kinnitatud image on:

```dockerfile
FROM liquibase/liquibase:4.29.2
```

Liquibase 4.29.2-ga töötab järgmine seadistus:
- `docker/liquibase/Dockerfile`: `COPY DSL/Liquibase/ /liquibase/`
- `docker-compose.yml` ja `docker-compose.ci.yml`: bind-mount `./DSL/Liquibase/` → `/liquibase/`
- `DSL/Liquibase/liquibase.properties`: `changelogFile: changelog.yaml`, `searchPath: /liquibase/`
- Liquibase käsk: `--defaultsFile=/liquibase/liquibase.properties update`

### Järgmine samm

Hinda Liquibase 5.x ühilduvust uuesti siis, kui saadaval on uuem 5.x väljalase või kui Liquibase dokumenteerib `searchPath`/`changelogFile` õige seadistamise Docker image'is. Hetkel ei luba 5.0.3 image lihtsat changelog faili teed tööle.

### Viited

- `docs/admin-deployment-guide.md` §1.4 — operatiivne kirjeldus
- `docker/liquibase/Dockerfile` — Liquibase versioon
- `docker-compose.yml`, `docker-compose.ci.yml` — PostgreSQL versioon
