# Contributing

This repository carries the specification corpus for the **eFTI Gate (EE)** — the Estonian national node in the EU eFTI network. Contributions are welcome — typo fixes, clarifications, gap reports, substantive AC changes. Implementation work lives in the runtime repositories, not here.

## Scope

This is **specification**, not implementation. Acceptance criteria describe **WHAT** the gate must do, never **HOW**. Implementation details (concrete classes, packages, frameworks, build files) belong in the runtime repos that consume this spec.

If a proposed change requires choosing a specific implementation technology to be expressible, it probably belongs downstream.

## How to suggest changes

- **Typos, broken links, clarification questions** → file a GitHub issue.
- **Substantive changes** (new ACs, contract-surface changes, schema edits) → open a PR with a short rationale; link the relevant epic.
- **Discussions on direction** → start a GitHub Discussion (preferred), or an issue.

Commit messages follow `area(scope): summary` — e.g. `docs(epics): add follow-up gate-mismatch AC`, `docs(specs): tighten errors.json codes`, `fix(diagrams): semicolons break mermaid sequence parser`.

## Branch strategy

- **`main`** is the integration branch. Tagged releases come from `main`.
- **Short-lived feature branches** (hours to a few days) are the default for everything else. Branch off `main`, push, open a PR, get it reviewed, merge back to `main`. Avoid long-lived branches.
- **`feature/planning`** was the consolidation branch used to ship v1.0; treat it as legacy after the v1.0 tag — new work targets `main`.

Branch names: `area/short-slug` — e.g. `docs/fix-mermaid-rendering`, `spec/openapi-pagination`, `fix/permission-matrix-row-drift`.

## Conventions

- **English only** in active docs. Estonian proper nouns (e.g. contracting-document titles) are kept verbatim with an inline English gloss.
- **Naming convention** — top-level titles and identifying mentions use **"eFTI Gate (EE)"** to disambiguate from other Member-State gates. Inside technical content describing gate behavior that applies to any eFTI Gate, "the gate" (lowercase) is fine.
- **Append-only DB philosophy** is non-negotiable. Every operational table is INSERT-only; state transitions are new rows. See [`docs/specs/db/README.md`](docs/specs/db/README.md).
- **Content-agnostic gate.** The gate routes by UIL and enforces access; it does not parse or transform dataset payloads (the XSLT subset filter is the only exception). Spec changes must not introduce business-content awareness on the gate.
- **Mermaid notes** — keep each `<br/>`-separated line ≤ 25 characters. GitHub's mermaid v10 strict-mode renderer does not auto-wrap; longer lines clip in state notes or balloon sequence-diagram width.
- **Mermaid in notes** — avoid `;` inside note text; mermaid's sequence-diagram grammar treats it as a statement terminator.
- **Cascading edits** — an epic-level contract change should ripple to `openapi.yaml`, `schema.sql`, `errors.json`, `permissions-matrix.md`, `logging-spec.md`, and `data-transformations.md` where applicable. The Spec-anchors table at the top of each epic lists the cascade surface.

## Validation before opening a PR

- **Relative markdown links resolve.** A quick Python one-liner across `docs/**/*.md` + `README.md` + `PROJECT-OVERVIEW.md` catches drift.
- **All Mermaid blocks parse** under mermaid v10 with `securityLevel: 'strict'` (this is what GitHub uses). The simplest harness: extract every `.mmd` and every fenced ` ```mermaid ` block, pipe each through `mermaid.parse()` under Node + jsdom.
- **No regressions in spec-anchors cross-references.** If you touched an OpenAPI operation, search the epics for the path — at least one epic should still reference it.

## License of contributions

By opening a PR you agree your contribution is released under the repository's current license (see [`LICENSE`](LICENSE)).

## Contact

- **Primary:** [@turnerrainer](https://github.com/turnerrainer) on GitHub.
- **Fallback:** `help@kemit.ee`.

KeMIT — Riigi Infosüsteemi Amet (Estonia) — reviews and merges.
