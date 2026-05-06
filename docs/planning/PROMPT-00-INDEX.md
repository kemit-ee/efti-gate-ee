# LLM Prompt Index for eFTI Gate v2.0 Specification Generation

## Purpose

This directory contains **LLM-optimized prompts** for Askend to use with their AI assistants (Claude, GPT-4, etc.) to generate the complete technical specification for eFTI Gate v2.0.

Each prompt is a **complete, standalone instruction** that can be copy-pasted into an LLM chat to generate a specific deliverable.

---

## How to Use These Prompts

### For Askend Team:

1. **Read the human feedback first**: [`ASKEND-FEEDBACK-EXECUTIVE-SUMMARY.md`](./ASKEND-FEEDBACK-EXECUTIVE-SUMMARY.md)
2. **Use prompts in order** (below) with your LLM of choice
3. **Validate each output** using success criteria in each prompt

### Recommended LLMs:

- **Claude 3.5 Sonnet** (best for code generation, long context)
- **GPT-4 Turbo** (good for structured data like JSON/YAML)
- **Claude Code** (best for database schema, has file access)

### Workflow:

```
For each prompt:
1. Open new LLM chat session
2. Upload/provide all "Input Materials" mentioned in prompt
3. Copy-paste entire prompt
4. Review generated output
5. Run validation tests from prompt
6. If validation fails → iterate with LLM (provide error messages)
7. Once validated → save to specs/ directory
8. Check off in deliverables checklist
```

---

## Prompt Execution Order

### Phase 1: Core Technical Specifications

**Execute in parallel** (3 separate LLM sessions):

| # | Prompt File | Output File | Dependencies |
|---|-------------|-------------|--------------|
| 1 | `PROMPT-01-OpenAPI-Generation.md` | `specs/openapi.yaml` | Current Gate code, epic docs |
| 2 | `PROMPT-02-Database-Schema-Generation.md` | `specs/db/schema.sql` | Current Gate DB, epic docs |
| 3 | `PROMPT-03-Error-Catalog.md` | `specs/errors.json` | OpenAPI spec (from #1) |

**Validation checkpoint**: After completing these 3, run:
- OpenAPI: Load in Swagger Editor → no errors
- Database: `psql < schema.sql` → database created
- Errors: Validate JSON syntax, all OpenAPI errors covered

### Phase 2: Logging & Transformations

**Execute in parallel**:

| # | Prompt File | Output File | Dependencies |
|---|-------------|-------------|--------------|
| 4 | `PROMPT-04-Logging-Spec.md` | `specs/logging-spec.md` | Current Gate logging, epic docs |
| 5 | `PROMPT-05-Data-Transformations.md` | `specs/data-transformations.md` | Current Gate XML code, epic docs |
| 6 | `PROMPT-06-Permissions-Matrix.md` | `specs/permissions-matrix.md` | OpenAPI spec, user roles from epics |

### Phase 3: Visual Documentation

**Execute sequentially** (each diagram builds on understanding):

| # | Prompt File | Output Files | Dependencies |
|---|-------------|--------------|--------------|
| 7 | `PROMPT-07-Mermaid-Diagrams.md` | `specs/diagrams/*.mmd` (25+ files) | All previous specs |

### Phase 4: Database Migrations & Documentation

| # | Prompt File | Output Files | Dependencies |
|---|-------------|--------------|--------------|
| 8 | `PROMPT-08-Database-Migrations.md` | `specs/db/migrations/V*.sql` | schema.sql from #2 |
| 9 | `PROMPT-09-Database-README.md` | `specs/db/README.md` | schema.sql, diagrams |

---

## Prompt Status Tracker

Use this to track your progress:

### Core Specs
- [ ] PROMPT-01: OpenAPI spec (`openapi.yaml`) - **VALIDATED**
- [ ] PROMPT-02: Database schema (`db/schema.sql`) - **VALIDATED**
- [ ] PROMPT-03: Error catalog (`errors.json`) - **VALIDATED**

### Extended Specs
- [ ] PROMPT-04: Logging spec (`logging-spec.md`) - **VALIDATED**
- [ ] PROMPT-05: Data transformations (`data-transformations.md`) - **VALIDATED**
- [ ] PROMPT-06: Permissions matrix (`permissions-matrix.md`) - **VALIDATED**

### Visual Docs
- [ ] PROMPT-07: Mermaid diagrams (`diagrams/*.mmd`, 25+ files) - **VALIDATED**

### DB Extras
- [ ] PROMPT-08: Database migrations (`db/migrations/`) - **VALIDATED**
- [ ] PROMPT-09: Database README (`db/README.md`) - **VALIDATED**

**Validation**: Each item checked off only when:
- Output file created
- Validation tests from prompt passed
- No placeholders ("TBD", "TODO", "example")
- Realistic data used (Estonian plates, real UUIDs, etc.)

---

## Input Materials Locations

**Askend must provide these to LLM**:

1. **Current Gate Source Code**: `{CURRENT_GATE_SOURCE}/`
   - Platform API: `gate/src/efti/platforms/PlatformRoutes.kt`
   - Authority API: `gate/src/efti/authorities/AuthorityRoutes.kt`
   - Admin APIs: `gate/src/admin/*AdminRoutes.kt`
   - Database: `gate/db/*.sql`
   - XML handling: `edelivery/src/edelivery/Xml.kt`

2. **Epic Documentation**: `efti_full_epics_en.md` (22 epics)

3. **Business Analysis**: Askend's own business analysis documents (functional requirements, user stories) - **NOT provided by KeMIT**

4. **Feedback Document**:
   - [`ASKEND-FEEDBACK-EXECUTIVE-SUMMARY.md`](./ASKEND-FEEDBACK-EXECUTIVE-SUMMARY.md) (verdict, gap summary, acceptance bar)

---

## Quality Checklist (Apply to ALL Outputs)

Before marking any prompt as complete, verify:

### Zero Tolerance Items
- [ ] **No placeholders**: Zero instances of "TBD", "TODO", "to be determined", "example", "lorem ipsum"
- [ ] **No generic examples**: Not "string", "test123", "user@example.com", "localhost:8080"
- [ ] **No broken references**: All `$ref` links resolve, all file paths valid

### Realistic Data Requirements
- [ ] **Estonian license plates**: "123ABC", "456XYZ" (not "ABC123", "plate1")
- [ ] **Gate IDs**: "eu-ee31", "eu-fi01", "eu-de01" (pattern: `eu-{country}{number}`)
- [ ] **UUIDs**: Valid v4 format from `uuidgen` (not 00000000-0000-0000-0000-000000000000)
- [ ] **Timestamps**: ISO 8601 format "2026-04-22T10:20:35Z" (not "now", "2021-01-01")
- [ ] **UN numbers**: 1203, 1950, 1965 (real dangerous goods codes, not "1234", "9999")
- [ ] **Country codes**: EE, FI, DE, SE (ISO 3166-1, not "XX", "AA")

### Language Requirements
- [ ] **Unambiguous**: "60 seconds" not "timeout period", "1-100 inclusive" not "small number"
- [ ] **With units**: "5 minutes" not "5", "10MB" not "large file"
- [ ] **With defaults**: "default: 60s, configurable via env var" not "configurable timeout"

### Consistency Requirements
- [ ] **Terminology**: Same term used throughout (dataset/identifier/authority/platform/gate)
- [ ] **Format**: Same date format, same UUID format, same naming conventions

---

## Success Criteria (All Prompts Complete)

Specification is ready for KeMIT review when:

✅ **All 9 prompts executed** and outputs validated
✅ **All files in specs/ directory** organized correctly
✅ **External developer test passed**: Give spec to independent dev → they can start coding without asking questions
✅ **Deliverables checklist**: All 25 verification questions answered YES
✅ **No dependencies on "to be provided later"**: Everything needed is in the spec

---

## Support & Questions

If any prompt is unclear or validation fails repeatedly:

1. **Re-read** the relevant section of [`ASKEND-FEEDBACK-EXECUTIVE-SUMMARY.md`](./ASKEND-FEEDBACK-EXECUTIVE-SUMMARY.md)
2. **Check Current Gate code** - working reference implementation exists
3. **Contact KeMIT**: Questions answered within 2 business days

**DO NOT PROCEED** to next prompt if previous validation fails. Iterate with LLM until validation passes.

---

## Version History

- v1.0 (2026-04-22): Initial prompt set created by KeMIT technical team
- Next: Askend executes prompts and generates specifications

---

**Ready to start?** Begin with `PROMPT-01-OpenAPI-Generation.md`
