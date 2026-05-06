# Executive Summary: Askend Specification Review

**Date**: 2026-04-22
**Project**: eFTI Gate v2.0 Complete Rewrite
**Reviewed Specification**: `efti_full_epics_en.md` (22 epics, 9 themes)
**Reviewer**: KeMIT/MKM Technical Team

---

## VERDICT: SPECIFICATION INCOMPLETE - NOT READY FOR DEVELOPMENT

**Compliance with Procurement Requirements**: **25% Complete**

Your specification `efti_full_epics_en.md` describes **WHAT** to build but lacks the **HOW** required for immediate development start per our procurement document "Tarkvara tehnilise analüüsi nõuded".

---

## CRITICAL GAPS SUMMARY

### Missing Technical Deliverables (9 files required)

| Deliverable | Status | Required For |
|-------------|--------|--------------|
| **openapi.yaml** | ❌ MISSING | Mock server creation, Swagger UI, API client generation |
| **db/schema.sql** | ❌ MISSING | One-command database setup, development/testing |
| **db/migrations/** | ❌ MISSING | Version control, zero-downtime deployments |
| **errors.json** | ❌ MISSING | Consistent error handling, monitoring, documentation |
| **logging-spec.md** | ⚠️ INCOMPLETE | ECS-compliant logging implementation |
| **data-transformations.md** | ❌ MISSING | XML/JSON conversions, eDelivery envelopes, subsetting |
| **diagrams/*.mmd** | ❌ MISSING | Visual documentation (25+ sequence/state/flow diagrams) |
| **permissions-matrix.md** | ⚠️ INCOMPLETE | Role-based access control implementation |
| **db/README.md** | ❌ MISSING | ER diagram, indexing strategy, query optimization |

### Missing Current Gate Business Logic (6 critical patterns)

Your specification does not preserve production-tested patterns from Current Gate (`efti-gate/` codebase):

1. **Broadcast-only-when-empty logic** - Privacy optimization missing from EPIC 4
2. **SSE streaming event types** - `gate`, `consignment`, `complete` events not specified
3. **XML manipulation helpers** - `dropXmlHeader()`, `dropXmlRoot()` patterns not documented
4. **Multi-platform user handling** - Single vs multiple platform logic missing from EPIC 3
5. **Identifier expiration timing** - Randomized 03:45-05:45 window not in EPIC 9
6. **Gate ping interval** - 5-minute interval not specified in EPIC 6

### Epic Acceptance Criteria Quality Issues

**Weaknesses found in all 22 epics**:
- ❌ Missing edge cases and negative test scenarios
- ❌ No rationale for non-obvious requirements (developer will ask "why?")
- ❌ Vague quantifiers ("configurable timeout" - what's the default? range? unit?)
- ❌ No error handling specifications per AC
- ❌ Missing performance constraints where critical

**Example - Current AC** (EPIC 4):
> "Empty result returns `200 OK` with an empty list (not 404)"

**Required Enhancement**:
> - [ ] **Happy path**: Search with 0 results returns `200 OK`, `Content-Type: application/json`, body `[]`
> - [ ] **Rationale**: Empty result is not an error (per HTTP semantics). 404 reserved for "endpoint not found"
> - [ ] **SSE variant**: Stream sends `event: complete` with no consignment events (not stream error)
> - [ ] **Logged as**: `{search.results.total: 0, search.broadcast: false}` at INFO level

---

## WHAT YOU MUST DELIVER

### Phase 1: Technical Specification Files (3-4 weeks)

**Week 1-2**:
1. **openapi.yaml** (2000-3000 lines) - All 50+ endpoints with realistic examples, RFC 7807 error responses
2. **db/schema.sql** (800-1000 lines) - Executable schema, triggers, indexes, COMMENTS, seed data
3. **errors.json** (300-500 lines) - 30+ error scenarios with examples, logging rules

**Week 2-3**:
4. **logging-spec.md** (40-60 pages) - ECS-compliant JSON format, 30+ scenarios, MDC correlation
5. **data-transformations.md** (30-40 pages) - 15+ transformation scenarios with full XML/JSON examples
6. **permissions-matrix.md** (25-35 pages) - Role × endpoint matrix, SQL filtering examples

**Week 3-4**:
7. **diagrams/*.mmd** (25+ files) - Sequence diagrams, state machines, data flows (Mermaid format)
8. **db/migrations/** (Flyway versioned scripts) - V001__initial_schema.sql, etc.
9. **db/README.md** (10-15 pages) - ER diagram, indexing strategy, migration procedures

### Phase 2: Epic Enhancements (1-2 weeks)

Update all 22 epics with:
- [ ] Preserved Current Gate patterns (broadcast logic, SSE events, XML helpers, timing specifics)
- [ ] Enhanced acceptance criteria (happy path + edge case + error handling + rationale)
- [ ] Technical constraints (Flyway/Liquibase ONLY, PostgreSQL 14+, RFC 7807 errors, ECS logging)
- [ ] Linked artifacts (reference diagrams, OpenAPI paths, DB tables, error codes)

**Template provided** in `CRITICAL-SPECIFICATION-GAPS.md` Section 3.2

---

## QUALITY REQUIREMENTS (ALL MUST PASS)

### Zero Tolerance Items

- ❌ **No placeholders**: Zero instances of "TBD", "TODO", "to be determined", "example"
- ❌ **No generic examples**: Not "string", "test123", "user@example.com", "localhost"
- ✅ **Realistic data**: Estonian plates "123ABC", gate IDs "eu-ee31", UN numbers "1203"
- ✅ **Unambiguous language**: "60 seconds" not "timeout period", "1-100 inclusive" not "small number"
- ✅ **Consistent terminology**: "dataset" everywhere (not mixed with "consignment payload")

### Verification Checklist (25 Questions)

Before resubmission, answer YES to ALL 25 questions in `ASKEND-DELIVERABLES-CHECKLIST.md`:

**Critical questions**:
1. Can a developer create a working mock server from `openapi.yaml` without asking questions?
2. Can `psql < db/schema.sql` create a working database without errors?
3. Can a developer implement logging by copy-pasting JSON examples?
4. Does every API endpoint have realistic request/response examples?
5. Are all Current Gate business logic patterns preserved?

**If ANY answer is NO**: Specification remains INCOMPLETE.

---

## ACCEPTANCE CRITERIA FOR RESUBMISSION

We will accept updated specification when:

✅ **All 9 technical files delivered** and reviewed
✅ **All 22 epics updated** with enhanced AC
✅ **All 25 verification questions**: YES
✅ **External developer review confirms**: "I can start coding immediately without asking questions"

---

## ESTIMATED EFFORT & TIMELINE

**Our Assessment**: 4-6 weeks of additional specification work

**Recommended Approach**:
- Week 1-2: Create OpenAPI, database schema, errors catalog
- Week 2-3: Create logging spec, data transformations, permissions matrix
- Week 3-4: Create 25+ Mermaid diagrams
- Week 4-5: Update all 22 epics with enhanced AC
- Week 5-6: Quality review, remove placeholders, realistic examples, completeness verification

**Budget Impact**: This is within scope of "Tarkvara tehnilise analüüsi nõuded" procurement. No additional budget required - specification work is what we contracted for.

---

## NEXT STEPS

### For Askend (IMMEDIATE):

1. **Review detailed feedback**: Read `CRITICAL-SPECIFICATION-GAPS.md` (complete technical requirements)
2. **Use self-assessment checklist**: Follow `ASKEND-DELIVERABLES-CHECKLIST.md` to track progress
3. **Clarify questions**: Schedule call with KeMIT if any requirements unclear (suggest: within 3 days)
4. **Commit to revised timeline**: Provide updated delivery schedule (target: 4-6 weeks from today)

### For KeMIT:

1. **Hold development procurement**: Do NOT start development RFP until specification complete
2. **Schedule interim reviews**: Weekly checkpoints with Askend to track progress
3. **Prepare external validation**: Identify independent developer to review final specification

---

## WHY THIS MATTERS

**Current specification would result in**:
- ❌ Development teams constantly asking questions (delays, cost overruns)
- ❌ Inconsistent implementations across teams (integration failures)
- ❌ Missing Current Gate production logic (functionality regressions)
- ❌ No testable acceptance criteria (failed QA, scope disputes)

**Complete specification ensures**:
- ✅ Development teams start coding immediately
- ✅ Parallel development by multiple teams possible
- ✅ All Current Gate functionality preserved
- ✅ Clear pass/fail criteria for acceptance testing
- ✅ Single source of truth for entire project

---

## CONCLUSION

Your epic-level work demonstrates good understanding of eFTI regulation and Estonian requirements. However, **epics alone are insufficient** for development procurement.

The missing technical specifications (OpenAPI, SQL schema, error catalog, logging spec, diagrams, permissions matrix, transformations) are **mandatory** per our procurement document and industry best practices.

**We are confident** you can deliver these specifications given:
- You have access to Current Gate codebase (~8,000 LOC of reference implementation)
- You have EU regulation expertise
- You have Estonian compliance knowledge (ANTS, ADR, X-Road)
- We provide detailed templates and examples in feedback documents

**Revised specification = solid foundation** for development phase success.

---

## ATTACHMENTS

1. **CRITICAL-SPECIFICATION-GAPS.md** - Complete technical feedback (all missing elements with examples)
2. **ASKEND-DELIVERABLES-CHECKLIST.md** - Self-assessment tool (track progress, verify completeness)

---

## CONTACT

**Questions or Clarifications**:
KeMIT Project Manager: [contact details]
Technical Review Team: [contact details]

**Response Time**: We will respond to questions within 2 business days.

---

**Document Status**: OFFICIAL FEEDBACK
**Required Action**: Askend must deliver updated specification meeting all requirements
**Timeline**: 4-6 weeks recommended
**Next Milestone**: Interim review in 2 weeks (checkpoint on OpenAPI, DB schema, errors.json)

---

**Approval**:
☐ Askend acknowledges requirements
☐ Askend commits to revised delivery timeline
☐ KeMIT approves timeline extension

**Signature**: ___________________ Date: ___________
