# ADR-001: Docker Compose container naming

**Decision (Sten Viljus, 2026-08-12):**

- `compose.yml` containers get the prefix `efti_` (e.g. `efti_database`, `efti_ruuter`)
- `compose.ci.yml` overrides `container_name` with the prefix `efti_ci_` (e.g. `efti_ci_database`, `efti_ci_ruuter`); the project name `efti_gate_ee_ci_stack` is set with the `-p` flag in every compose command (the `name:` field of override files is ignored by Docker Compose)

This avoids name conflicts between dev and CI stacks running in parallel on the same host.
