# What to read next

You now have two gates exchanging AS4 messages, sample data in both, your own certificates, and a
collection that checks every endpoint of a gate, from Postman or with Newman.

**In this book:**

- [By role](../roles/index.md): a path for your role, whether you decide, integrate, deploy, operate or
  administer.
- [Use cases](../use-cases/index.md): eight complete tasks, run end to end.
- [Endpoint map](../api/index.md): every endpoint by audience, then one page per endpoint.
- [Ports and credentials](../reference/ports-and-credentials.md): every address and dev secret in
  one place.
- [Sample data](../reference/sample-data.md): each seeded dataset, and which identifiers prove a
  search crossed gates.
- [The Postman collection](../reference/collection.md): every request, its method and URL.
- [Troubleshooting](../reference/troubleshooting.md) and [Known issues](../reference/known-issues.md).

**In the repository** (links go to the `dev` branch):

| Topic | Where |
|---|---|
| The local network's files | [`local/README.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/local/README.md) |
| One gate with everything (UI, TARA login, mocks, CronManager, observability) | `docker compose up --build -d` from the repo root; see [`README.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/README.md) |
| The X-Road provider API, case by case with verified answers | [`docs/test-cases/xroad-provide.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/test-cases/xroad-provide.md) |
| Registries: gates, platforms, authorities, certificates | [`registry/README.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/registry/README.md) |
| AS4 key rotation and certificate expiry | [`docs/runbooks/certificate-expiry.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/certificate-expiry.md) |
| Trust boundaries between gate, peers and platforms | [`docs/architecture/security-and-compliance/perimeter-trust-model.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/architecture/security-and-compliance/perimeter-trust-model.md) |
| Conventions for changing the gate (DSL, SQL, Kotlin, tests) | [`AGENTS.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/AGENTS.md) |

**When you're done** (in `local/`):

```bash
docker compose --env-file gates/g2.env down       # stop G2, keep its data
docker compose --env-file gates/g1.env down       # stop G1, keep its data
docker compose --env-file gates/g1.env down -v    # or: stop and delete the data
docker network rm efti-g2g                        # optional; the next up creates it again
```
