# DSL audit baseline and exceptions

Issue #293: `dsl-lint --audit` (the engine behind Ruuter's `GET /_/audit/dsl`) must report `errors: 0`.
CI (`dsl-validate` in GitHub Actions and `validate:dsl` in GitLab) runs it on both DSL roots and fails on any error.

Snapshot with `turnerrainer/ruuter:0.12.1-rc`: `docs/audit/dsl-audit-baseline.json`
(DSL/Ruuter: 0 errors, 0 warnings; DSL/Ruuter-xroad-mock: 0 errors, 0 warnings).
Regenerate with `dsl-lint --audit --json --dsl <root> --constants <ini>`.

There are no open exceptions. Any future warning needs an entry here naming the rule code, the DSL path,
the owner, the close-by date and the operator who signed off.
