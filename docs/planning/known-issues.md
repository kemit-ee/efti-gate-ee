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
