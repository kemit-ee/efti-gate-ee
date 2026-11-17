# Gate administrator

> **Goal:** you can log in to the admin UI, manage who else may administer the gate, read the
> registries, look at consignments and the audit log, and request registry changes.

## Your role

You run the gate day to day through the **admin UI** (or its API). You decide who else gets access,
you check what is registered, and you read the audit log. You do **not** edit the gate, platform or
authority registries in the UI: they are files in git, and the UI shows them read-only.

> **Everyone is an administrator.** There are no roles. Any active user can manage users, revoke
> tokens, delete consignments and read the audit log. Adding a user is therefore a security-relevant
> act. Role separation is not implemented; see the [Permissions matrix](../data/permissions.md).

## Path

1. **Be the first administrator.** In a production environment the first user is inserted by hand by
   the deployer: [First administrator](./first-admin.md). In the local network a development user
   exists.
2. **Learn the UI:** [Admin UI guide](./admin-ui.md), with screenshots.
3. **Walk through the tasks end to end on the API:**
   [Manage the gate](../use-cases/administrator.md): log in, check the registries, add a user, revoke
   a token, look at consignments and the audit log, log out.
4. **Request registry changes:** [Change the registry](./registry-changes.md).
5. **Know the rules:** [Permissions matrix](../data/permissions.md) and
   [Audit and GDPR](../architecture/security-and-compliance/audit_and_gdpr.md).
6. **API reference for scripting:** [Administrators](../api/admin/index.md), with
   [Users](../api/admin/users.md), [Registries](../api/admin/registries.md),
   [Consignments](../api/admin/consignments.md) and [Audit log](../api/admin/audit.md).

## Who can do what

| Task | Where | Notes |
|---|---|---|
| Log in | TARA, through TIM | Works only if your personal code has an active user row |
| Add, rename, delete a user | **Users** view | The personal code of an existing user cannot be changed: delete and add |
| Revoke a user's tokens | **Users** → *Revoke token* | The user must log in again |
| See peer gates, platforms, authorities | **Gates**, **Platforms**, **Authorities** | Read-only. The platform API key is never shown |
| Change those registries | Pull request on `registry/`, then `registry-build` | [Change the registry](./registry-changes.md) |
| Browse and delete consignments | **Consignments** | Deleting writes a new "deleted" version; history is kept |
| Read the audit log | API: [Audit log](../api/admin/audit.md) | Personal codes are hashed |

## Good practice

- Log out on a shared computer, then close the browser.
- Check the record id before deleting a user or a consignment.
- Before changing an authority's subsets, review the whole list, not only the change.
- To pause an integration, ask for `status: DISABLED` in the registry rather than deleting the file;
  the configuration stays.
- Keep API keys and certificate private keys in the secret store, never in tickets or chat.
- When a colleague leaves: revoke their token, then delete their user.

## When something is wrong

| Symptom | Check |
|---|---|
| Login returns you to authentication | Your personal code is not registered, the row is inactive, or your token was revoked. Ask another administrator. With none left, the deployer repeats [First administrator](./first-admin.md) |
| A peer gate or platform is missing or `DISABLED` | The file in `registry/`. Ask the technical team; a change needs a new build of the registry |
| A peer gate does not answer | Open its *Details*: check the URL and certificate, then confirm with the partner. Send the time and the gate id to technical support |
| A platform's key is lost or leaked | A new key is put in the registry file and applied; the operator receives it securely. The old key stops working when the change is applied |

More: [Troubleshooting](../reference/troubleshooting.md) and, for operators, [Operator](./operator.md).
