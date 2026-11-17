# First administrator

> **Goal:** a new production environment has one active administrator who can log in to the admin
> UI with TARA, so that everyone else can be added in the UI.

This page is an English version of
[`docs/specs/deploy/first-admin.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/specs/deploy/first-admin.md),
which is the source (Estonian). It is also published as a child page of the Confluence deployment
guide.

In a new production environment nobody can log in to the admin UI until the database holds at least
one active user. The gate does not create users itself. Every request to `/admin/**` checks that an
**active** row in `users` exists for the caller's TARA personal code. The development seed
(Super Admin `60001019906`) is in the Liquibase context `dev` and **must not reach production**
([Deployer](./deployer.md#liquibase-context)). So the first user is inserted by hand, once, with the
steps below. After that all other users are added in the **Users** view.

There is no local fallback administrator (no break-glass password login). Login goes only through TARA
and TIM. If the only administrator loses access (personal code, TARA account), repeat this
procedure.

> **Every user has full access** to the admin UI: user management, token revocation, deleting
> consignments and reading the audit log. There are no roles. Every `users` row is an administrator
> right, so inserting the first row is a security-critical change.

## Prerequisites

- Write access to the database: a PostgreSQL role that may write `users` and `audit_log` (for
  example the application role `app`, or the environment's admin role). How you get access depends
  on the environment (for example `kubectl exec` or a bastion). Never put the password or
  connection string on a command line or in this procedure.
- The first administrator's **11-digit Estonian personal code without a country prefix** (for
  example `60001019906`). This is the TIM `userinfo` field `personalCode`, which the gate compares to
  `users.tara_sub`. Have two people verify the code and the name before it is entered: one enters,
  the other checks.
- A change number from your change management, to record in the audit.
- TARA, TIM, `ruuter` and `resql` are running, so a login works after the insert.

## 1. Check that no user exists and no dev seed is present

```sql
SELECT id, tara_sub, name, is_active, created_at
FROM users
ORDER BY created_at;
```

In production the result must be **empty**. If it holds `60001019906` (Super Admin), the dev seed
reached production. That is a deployment error (Liquibase context), not something to fix here. Tell
the deployer, and remove that row only after the first real administrator is in (step 5).

## 2. Insert the user and an audit row in one transaction

Replace `PERSONAL_CODE`, `NAME` and `CHANGE_ID`. `users` is append-only: a row is never changed or
deleted; new states are new rows.

```sql
BEGIN;

WITH new_user AS (
  INSERT INTO users (id, tara_sub, name)
  VALUES (uuid_generate_v4(), 'PERSONAL_CODE', 'NAME')
  RETURNING id
)
INSERT INTO audit_log (user_id, action, resource, resource_id, details)
SELECT NULL, 'user.create_manual', 'user', id::text,
       jsonb_build_object('change', 'CHANGE_ID', 'reason', 'first administrator')
FROM new_user;

COMMIT;
```

The audit row holds neither the personal code nor the name, only the user `id` and the change number.
`user_id` is `NULL` because the actor is not yet a user of the gate.

## 3. Verify

```sql
SELECT id, tara_sub, name, is_active, created_at
FROM users
WHERE tara_sub = 'PERSONAL_CODE'
ORDER BY created_at DESC;
```

Exactly **one** row with `is_active = true`. Two rows mean the insert was repeated and the same
personal code has two different `id`s. Keep one and deactivate the other as in step 5, using that
row's `id`.

## 4. Log in

Open the admin UI and sign in with TARA using that personal code. The **Users** view must open and
show the same user. Add the other administrators there. Each addition writes an audit row
`user.create` with the actor's `id`.

If login returns `401`:

| Cause | Check |
|---|---|
| Personal code does not match (prefix, space, wrong digit) | `personalCode` in the TIM `userinfo` response and `users.tara_sub` must be **identical** |
| The row is inactive | `SELECT ... is_active` (step 3). To activate, add a new row with the same `id` and `is_active = TRUE` (append-only rule) |
| The token was revoked | `token_revoked_at` is set and the token is older. Log in again |
| TARA or TIM is down | Runbook [`tara-authentication-outage`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/tara-authentication-outage.md) |

## 5. Correct a mistake (wrong code, duplicate, dev seed)

A row is not deleted: **deactivate** it with a new row, then add the correct one (step 2). Replace
`USER-ID` with the `id` from step 3 (it also tells apart two rows with the same personal code):

```sql
BEGIN;

INSERT INTO users (id, tara_sub, name, is_active)
SELECT id, tara_sub, name, FALSE
FROM users
WHERE id = 'USER-ID'
ORDER BY created_at DESC, revision DESC
LIMIT 1;

COMMIT;
```

After this, that row can no longer sign in. If another active row exists for the same personal
code, it still can. **Do not deactivate the last active administrator** before the new one is in and
a login has been verified, or you lock yourself out and must repeat steps 2 to 4.

## Notes

- The two-person check and the change number are organisational requirements. The gate does not
  enforce them.
- Every later user creation, change, deletion and token revocation writes an audit row
  (`user.create`, `user.update`, `user.delete`, `user.revoke_token`) with the actor's `id`. You can
  check them through the [audit log API](../api/admin/audit.md).
- Role separation (for example read-only) does not exist; it would be separate work (#321).
