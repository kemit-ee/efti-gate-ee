# Docker Compose konteinerite nimetamine

**Otsus (Sten Viljus, 2026-08-12):**

- `compose.yml` konteinerid saavad eesliite `efti_` (nt `efti_database`, `efti_ruuter`)
- `compose.ci.yml` kirjutab `container_name` üle eesliitega `efti_ci_` (nt `efti_ci_database`, `efti_ci_ruuter`); projekti nimi `efti_gate_ee_ci_stack` seatakse `-p` lipuga igas compose käsus (override-failide `name:` väli ignoreeritakse Docker Compose poolt)

See väldib nimekonfliktid sama hosti peal paralleelselt töötavate dev- ja CI-stackide vahel.
