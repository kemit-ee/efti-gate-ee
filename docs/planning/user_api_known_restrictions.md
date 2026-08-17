# Users API — Auth-sõltuvad piirangud

See dokument kirjeldab kolme `GET /POST /DELETE /api/v1/users` endpointide reeglit, mis on OpenAPI spetsifikatsioonis nõutud, kuid praegu **rakendamata** kuna vajavad TARA JWT autentimiskonteksti. Kõik Admin API guard-failid on hetkel `allow-all` (vt `docs/planning/rest-api-disainijuhend.md`).

---

## 1. `POST /api/v1/users` — `tara_sub` duplikaadi 409 kontroll

**Staatus: ✅ TOIMIV** — ei vaja auth konteksti.

Kontroll on päriselt implementeeritud: `check_tara_sub_exists.sql` pärib DB-st kas aktiivne kasutaja sama `tara_sub`-ga on olemas. Kui jah, tagastatakse 409 Conflict.

Kommenteeritud kood `POST/api/v1/users.yml` ülemisel osal näitab JWT valideerimise ja `checkWriteAccess` loogika kohta, kuhu need sammud lisada.

---

## 2. `DELETE /api/v1/users/{userId}` — admin ei saa kustutada iseennast

**Staatus: ⚠️ RAKENDAMATA** — vajab JWT konteksti.

Spetsifikatsioon (`permissions-matrix.md` §3.3):
> `DELETE /api/v1/users/{userId}` — `PUT 404 on unknown id; cannot delete self → 400 BAD_REQUEST_GENERAL`

Praegu `DELETE /efti/api/v1/users/delete?userId=` kustutab iga kasutaja ilma kontrollita — admin saab kustutada ka iseennast.

**Kuidas sisse lülitada pärast auth implementeerimist:**

`DSL/Ruuter/efti/DELETE/api/v1/users/delete.yml` faili ülaosas on kommenteeritud pseudo-DSL plokk:

```yaml
# check_not_self:
#   switch:
#     - condition: ${caller_result.response.body[0].id == incoming.params.userId}
#       next: delete_self_error
#   next: delete_user
#
# delete_self_error:
#   return: '{"error": "BadRequest", "detail": "Admin cannot delete their own account"}'
#   status: 400
#   next: end
```

Sammud:
1. Lisa JWT valideerimise voog (vt `POST/api/v1/users.yml` kommenteeritud auth plokk)
2. Resolveri kasutaja (`get_user_by_tara_sub.sql` — tuleb luua, vt järgmine samm)
3. Kommenteeri välja `check_not_self` ja `delete_self_error` plokid

**Vajab lisaks uut SQL faili:** `get_user_by_tara_sub.sql` — pärib aktiivse kasutaja JWT `sub` väärtuse järgi, mida saab kasutada nii caller-identiteedi resolveerimisel kui ka self-delete kontrollil.

---

## 3. `GET /api/v1/user` — praeguse autenditud kasutaja profiil

**Staatus: ❌ PUUDUB** — vajab JWT `sub` konteksti, DSL faili pole loodud.

Spetsifikatsioon (`openapi.yaml` §1519):
> `GET /api/v1/user` — Returns the profile of the currently authenticated user.

See endpoint eeldab et gate teab JWT `sub` väärtust (`tara_sub`), mille kaudu saab DB-st kasutaja rea leida. Ilma JWT valideerimiseta pole praeguses implementatsioonis praeguse kasutaja identiteeti.

**Kuidas implementeerida pärast auth lisamist:**
1. Loo `DSL/Ruuter/efti/GET/api/v1/user.yml`
2. Lisa JWT valideerimise samm (vt `POST/api/v1/users.yml` kommenteeritud auth plokk)
3. Extrakti `sub` JWT-st → päringusse `get_user_by_tara_sub.sql`
4. Tagasta 200 koos kasutaja profiiliga (sama skeem nagu `GET /users/{userId}`)

---

## Seotud failid

| Fail | Märkus |
|---|---|
| `DSL/Ruuter/efti/POST/api/v1/users.yml` | Kommenteeritud auth placeholder (JWT validate + checkWriteAccess) |
| `DSL/Ruuter/efti/DELETE/api/v1/users/delete.yml` | Kommenteeritud self-delete keelu placeholder |
| `docs/specs/permissions-matrix.md` §3.3 | Admin API täielik permissions maatriks |
| `docs/planning/known-issues.md` KI-004 | Lühikokkuvõte |
