# DSL audit baseline and exceptions

Issue #293: `dsl-lint --audit` (the engine behind Ruuter's `GET /_/audit/dsl`) must report `errors: 0`.
CI (`dsl-validate` in GitHub Actions and `validate:dsl` in GitLab) runs it on both DSL roots and fails on any error.

Snapshot taken with `turnerrainer/ruuter:0.12.1-rc`: `docs/audit/dsl-audit-baseline.json`
(DSL/Ruuter: 0 errors, 221 warnings; DSL/Ruuter-xroad-mock: 0 errors, 16 warnings).
Regenerate with `dsl-lint --audit --json --dsl <root> --constants <ini>`.

## Open warnings by rule

| DSL root | Rule | Count |
|---|---|---|
| `DSL/Ruuter` | `declaration.body.under_declared` | 22 |
| `DSL/Ruuter` | `declaration.headers.under_declared` | 2 |
| `DSL/Ruuter` | `declaration.internal_missing` | 70 |
| `DSL/Ruuter` | `declaration.legacy_flat_allowlist` | 6 |
| `DSL/Ruuter` | `declaration.params.under_declared` | 28 |
| `DSL/Ruuter` | `declaration.returns_missing` | 70 |
| `DSL/Ruuter` | `flow.unreachable_step` | 4 |
| `DSL/Ruuter` | `yaml.em_dash` | 19 |
| `DSL/Ruuter-xroad-mock` | `declaration.body.under_declared` | 2 |
| `DSL/Ruuter-xroad-mock` | `declaration.internal_missing` | 7 |
| `DSL/Ruuter-xroad-mock` | `declaration.returns_missing` | 7 |

## Triage

Owner and close-by date are filled in by the maintainers when each item is accepted; an item without both is open work, not an exception.

| Rule | Disposition | Owner | Close-by |
|---|---|---|---|
| `declaration.internal_missing` | Pending the operator decision on `declarations.default_internal` (out of scope for #293). | TBD | TBD |
| `declaration.returns_missing` | Fix: add `returns:` schemas (OpenAPI quality only, no runtime effect). | TBD | TBD |
| `declaration.params.under_declared` / `declaration.headers.under_declared` / `declaration.body.under_declared` | Fix: document the referenced inputs in `declaration`. Headers stay in prose per AGENTS.md. | TBD | TBD |
| `declaration.legacy_flat_allowlist` | Fix: migrate to structured `allowlist.body`. | TBD | TBD |
| `yaml.em_dash` | Fix: replace U+2014 with ASCII in descriptions. | TBD | TBD |
| `flow.unreachable_step` | Review: dead steps, remove or wire in. | TBD | TBD |
