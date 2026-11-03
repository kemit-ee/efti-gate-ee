# Test cases

Hand-runnable sample requests with the response each one gives **today**. They complement the
automated suites (`tests/*.http`, `DSL-tests/`): those assert behaviour in CI, these are for a person
or a script that probes a deployed environment (dev, test, prelive) and wants to know what a correct
answer looks like.

| File | Covers |
|---|---|
| [xroad-provide.md](xroad-provide.md) | The X-Road provider adapter, `/xroad/provide/**` (`ruuter-xroad`) |
| [admin-api.md](admin-api.md) | The admin API, `/admin/v1/**` (needs an admin token) |
| [data/README.md](data/README.md) | Six test consignments as an opt-in Liquibase changeset, and how to load them |
| [xroad-smoke.sh](xroad-smoke.sh) | The status-code checks of `xroad-provide.md` as one script |

## Reading a case

Every case has a request, the expected status and body, and a **Source**:

- **observed** — the answer was seen from a real environment (named, with the date). `local` is the
  compose stack with the [test data](data/README.md) loaded. Fields that vary per environment are
  marked in the case.
- **derived** — taken from the DSL or the E2E tests, not yet seen on a deployment.

When you observe a derived case on a deployment, change its source to `observed (<env>, <date>)`.
When behaviour changes, update the expected answer in the same change as the code.

## Adding a case

1. Pick the next number in the file's section, give it a name that says the outcome
   (`transport-means: missing identifier → 400`).
2. Put the request as a `curl` that runs as written, with only the base URL as a variable.
3. State the status and the body. Quote real bodies in full; write `…` only for fields that differ
   per environment and say so.
4. Set the source. If the answer is a known defect, mark the case **Known issue** and link what
   fixes it, so the case can be flipped when the fix is deployed.
5. If the case is a status check, add it to the matching script too.

## Environments

The X-Road hosts (`*.xtpnl.kemitaws.ee`) sit behind an ALB whose security group allows only the
Security Server and explicitly added addresses; elsewhere the connection times out (curl `000`).
The public UI/mock hosts (`*.kemitaws.ee`) do not have that restriction.
