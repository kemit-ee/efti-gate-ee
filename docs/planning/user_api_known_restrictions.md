# Users API — ajalooline auth-piirangute dokument (VANANENUD)

> ⚠️ **See dokument on suures osas vananenud.** Kõik allpool kirjeldatud piirangud eeldasid
> TARA JWT autentimiskonteksti puudumist. Autentimine (`admin/.guard.yml`, `check-admin-authority`)
> on nüüdseks teostatud kogu `/admin/v1/**` peal, ning kõik kolm allpool kirjeldatud punkti on
> **valminud**. Dokument on jäetud alles ajaloolise kontekstina; vt
> [docs/specs/api_endpoints.md](../specs/api_endpoints.md) jaotisi
> [7. Admin — Users](../specs/api_endpoints.md#7-admin--users) ja
> [9.2 Users (Admin)](../specs/api_endpoints.md#92-users-admin) värske seisu jaoks.

Algselt kirjeldas see dokument kolme `GET/POST/PUT/DELETE /admin/v1/users` (varem valesti
viidatud kui `efti/api/v1/users`) endpointide reeglit, mis olid OpenAPI spetsifikatsioonis
nõutud, kuid rakendamata, kuna vajasid TARA JWT autentimiskonteksti.

---

## 1. `POST /admin/v1/users` — `taraSub` duplikaadi 409 kontroll

**Staatus: ✅ TOIMIV** — sõltumatu autentimiskontekstist.

Kontroll on päriselt implementeeritud: `check_tara_sub_exists.sql` pärib DB-st kas aktiivne kasutaja sama `taraSub`-ga on olemas. Kui jah, tagastatakse 409 Conflict.

**Ruuter DSL:** `DSL/Ruuter/admin/POST/v1/users.yml`

---

## 2. `DELETE /admin/v1/users/{userId}` — admin ei saa kustutada iseennast

**Staatus: ✅ TEOSTATUD.**

Spetsifikatsioon (`permissions-matrix.md` §3.3):
> `DELETE /api/v1/users/{userId}` — `PUT 404 on unknown id; cannot delete self → 400 BAD_REQUEST_GENERAL`

**Ruuter DSL:** `DSL/Ruuter/admin/DELETE/v1/users.yml` (mitte `efti/DELETE/api/v1/users/delete.yml`,
mida see fail kunagi ei eksisteerinud — vt allpool).

Aktiivne kood (mitte enam kommenteeritud pseudo-DSL):

```yaml
# Admin is enforced by admin/.guard.yml, which also exposes the caller row as ${caller}.
check_not_self:
  switch:
    - condition: ${caller.id == incoming.params.pathParams[0]}
      next: delete_self_error
  next: delete_user

delete_self_error:
  return: '{"error": "BadRequest", "detail": "Admin cannot delete their own account"}'
  status: 400
  wrapper: false
  next: end
```

`admin/.guard.yml` autendib helistaja (`check-admin-authority`) ja lisab konteksti `${caller}`
rea; käsitleja ei pea kutsujat eraldi lahendama.

---

## 3. `GET /auth/user` — praeguse autenditud kasutaja profiil

**Staatus: ✅ TEOSTATUD** — dokument väitis varem, et DSL-faili pole loodud; tegelikult on see
omaette `auth/` Ruuter projektis, mitte `efti/GET/api/v1/user.yml`-is.

Spetsifikatsioon (`openapi.yaml` §1519):
> `GET /api/v1/user` — Returns the profile of the currently authenticated user.

**Ruuter DSL:** `DSL/Ruuter/auth/GET/user.yml`, kaitstud `DSL/Ruuter/auth/GET/.guard.yml`-ga
(`check-user-authority` — iga autenditud kasutaja, mitte ainult admin). Guard lahendab `${caller}`
rea; handler tagastab selle otse (`wrapper: false`), skoobitud kutsuja enda reale.

```
GET /auth/user
Authorization: Bearer <gate JWT>

→ 200 OK
{ "id": "...", "taraSub": "...", "name": "...", "isUserActive": true, ... }
```

---

## Seotud failid

| Fail | Märkus |
|---|---|
| `DSL/Ruuter/admin/POST/v1/users.yml` | `taraSub` duplikaadi 409 kontroll |
| `DSL/Ruuter/admin/DELETE/v1/users.yml` | Self-delete keeld (`check_not_self`) |
| `DSL/Ruuter/auth/GET/user.yml` | Praeguse kasutaja profiil |
| `DSL/Ruuter/admin/.guard.yml` | Admin autentimise projektitasemeline guard |
| `DSL/Ruuter/auth/GET/.guard.yml` | Any-authenticated-user guard |
| `AGENTS.md` §"Guard map" | Ruuter guard-käitumise ülevaade kõigi projektide kohta |
| `docs/specs/permissions-matrix.md` §3.3 | Admin API täielik permissions maatriks |
| `docs/planning/known-issues.md` KI-004 | Lühikokkuvõte (kontrolli, kas endiselt asjakohane) |
