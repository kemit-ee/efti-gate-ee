# Esimese administraatori sisseviimine (tootmine)

Uues tootmiskeskkonnas ei saa keegi haldusliidesesse sisse, kuni andmebaasis on vähemalt üks aktiivne kasutaja. Värav ei loo kasutajaid ise: iga päring haldusliidese (`/admin/**`) vastu kontrollib, et TARA isikukoodiga on andmebaasi tabelis `users` **aktiivne** rida. Dev-seeme (Super Admin `60001019906`) on Liquibase'i kontekstis `dev` ja **ei tohi tootmisse jõuda** (vt paigaldusjuhend, peatükk 5 „Liquibase kontekst"), seega esimene kasutaja lisatakse käsitsi, üks kord, käesoleva juhendi järgi. Pärast seda lisatakse kõik teised kasutajad haldusliidese vaates **Kasutajad** (vt kasutusjuhend, peatükk 5).

Kohalikku tagavara-administraatorit (*break-glass*, parooliga sisselogimist) ei ole: sisselogimine käib ainult TARA ja TIM kaudu. Kui ainus administraator kaotab ligipääsu (isikukood, TARA konto), tehakse sama sisseviimine uuesti selle juhendi järgi.

> Haldusliideses on **igal kasutajal täielik ligipääs** (kasutajate haldus, tokenite tühistamine, saadetiste kustutamine, auditilogi lugemine); rolle ei ole. Seetõttu on iga `users` rida administraatori õigus, ja esimese rea sisestamine on turvakriitiline muudatus.

## Eeldused

- Andmebaasi kirjutusõigusega ligipääs (PostgreSQL roll, mis tohib tabelisse `users` ja `audit_log` kirjutada, nt rakenduse roll `app` või keskkonna haldusroll; ligipääsu viis sõltub keskkonnast, nt `kubectl exec` või bastion). Parooli ega ühenduse andmeid ei kirjutata käsureale ega sellesse juhendisse.
- Esimese administraatori **11-kohaline Eesti isikukood ilma riigi eesliiteta** (nt `60001019906`): see on TIM-i `userinfo` väli `personalCode`, mida värav võrdleb tabeli `users` veeruga `tara_sub`. Kontrolli isikukood ja nimi kahe inimese peale (üks sisestab, teine kontrollib), enne kui sisestad.
- Muudatuse number (muudatushaldus), mida märkida auditi.
- `TARA`, `TIM`, `ruuter` ja `resql` töötavad (sisselogimine peab pärast sisseviimist toimima).

## 1. Kontrolli, et kasutajat veel ei ole ja dev-seemet ei ole

```sql
SELECT id, tara_sub, name, is_active, created_at
FROM users
ORDER BY created_at;
```

Tulemus peab tootmises olema **tühi**. Kui seal on rida `60001019906` (Super Admin), on dev-seeme tootmisse sattunud: see on viga paigalduses (Liquibase kontekst), mitte midagi, mida käesolevaga parandada; teavita paigaldajat ja eemalda see rida alles pärast esimese tõelise administraatori sisseviimist (samm 5).

## 2. Lisa kasutaja ja auditirida ühe tehinguna

Asenda `ISIKUKOOD`, `NIMI` ja `MUUDATUSE-ID`. Tabel `users` on ainult-lisamise (*append-only*): rida ei muudeta ega kustutata, uued olekud lisatakse uue reana.

```sql
BEGIN;

WITH new_user AS (
  INSERT INTO users (id, tara_sub, name)
  VALUES (uuid_generate_v4(), 'ISIKUKOOD', 'NIMI')
  RETURNING id
)
INSERT INTO audit_log (user_id, action, resource, resource_id, details)
SELECT NULL, 'user.create_manual', 'user', id::text,
       jsonb_build_object('change', 'MUUDATUSE-ID', 'reason', 'first administrator')
FROM new_user;

COMMIT;
```

Auditirida ei sisalda isikukoodi ega nime, ainult kasutaja `id` ja muudatuse numbri. `user_id` on `NULL`, sest tegutseja ei ole veel värava kasutaja.

## 3. Kontrolli sisseviimist

```sql
SELECT id, tara_sub, name, is_active, created_at
FROM users
WHERE tara_sub = 'ISIKUKOOD'
ORDER BY created_at DESC;
```

Peab andma täpselt **ühe** rea, `is_active = true`. Kahe rea korral on sisestus korratud ja samal isikukoodil on kaks erinevat `id`-d: jäta kehtima üks ja desaktiveeri teine sammu 5 järgi, kasutades selle rea `id`-d.

## 4. Logi sisse

Ava haldusliides ja logi sisse TARA-ga selle isikukoodiga. Haldusliidese vaade **Kasutajad** peab avanema ja näitama sama kasutajat. Lisa seal teised administraatorid (kasutusjuhend, peatükk 5); iga lisamine kirjutab auditirea `user.create` koos lisaja `id`-ga.

Kui sisselogimine annab 401:

| Põhjus | Kontroll |
|---|---|
| Isikukood ei klapi (eesliide, tühik, vale number) | `personalCode` TIM-i `userinfo` vastuses ja `users.tara_sub` peavad olema **sõna-sõnalt** samad |
| Rida on mitteaktiivne | `SELECT ... is_active` (samm 3); aktiveerimiseks lisa uus rida samale `id`-le, `is_active = TRUE` (ainult-lisamise reegel) |
| Token on tühistatud | `token_revoked_at` on seatud ja token on sellest vanem; logi uuesti sisse |
| TARA või TIM ei tööta | vt `docs/runbooks/tara-authentication-outage.md` |

## 5. Vea parandamine (vale isikukood, kordus, dev-seeme)

Rida ei kustutata: **desaktiveeri** see uue reana ja lisa õige uuesti (samm 2). Asenda `KASUTAJA-ID` sammu 3 tulemuse veeruga `id` (see eristab ka kahte rida samal isikukoodil):

```sql
BEGIN;

INSERT INTO users (id, tara_sub, name, is_active)
SELECT id, tara_sub, name, FALSE
FROM users
WHERE id = 'KASUTAJA-ID'
ORDER BY created_at DESC, revision DESC
LIMIT 1;

COMMIT;
```

Pärast seda ei pääse selle rea alusel enam sisse (`is_active = false`); kui samal isikukoodil on veel teine aktiivne rida, pääseb selle kaudu endiselt. **Ära desaktiveeri viimast aktiivset administraatorit**, enne kui uus on sisse viidud ja temaga sisselogimine on kontrollitud, muidu jääd haldusliidesest välja ja pead sammud 2–4 kordama.

## Märkused

- Kahe silma printsiip ja muudatuse number on organisatsiooniline nõue, mida värav ise ei jõusta.
- Iga hilisem kasutaja lisamine, muutmine, kustutamine ja tokeni tühistamine kirjutab auditirea (`user.create`, `user.update`, `user.delete`, `user.revoke_token`) koos tegutseja `id`-ga: seda saab kontrollida haldusliidese auditi vaates.
- Rolli- või õiguste jaotust (nt „ainult lugemine") ei ole; kui seda vajatakse, on see eraldi arendus (#321).
