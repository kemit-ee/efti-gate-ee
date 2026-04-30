# EPIC 18 — Test Coverage and Quality

> Part of [Theme 8](theme_8_en.md)

**AS A** developer  
**I WANT** automated tests covering the core business logic  
**SO THAT** regressions are caught before reaching production

#### Acceptance Criteria

##### Unit tests

**Happy path:**
- [ ] Business logic layer coverage ≥ 80%: local vs remote routing, broadcast parallelism, error handling (gate offline, invalid XML, timeout)
- [ ] Access control unit tests: all role combinations × endpoints, Super Admin, regular Admin, denial
- [ ] User management unit tests: role restriction, subset validation, self-deletion prevention
- [ ] Request ID validator unit tests: duplicate detection, TTL expiry behaviour
- [ ] eDelivery message parsing: all message types, unknown compression type, unknown rootTag

**Edge cases tested:**
- [ ] `broadcast-only-when-empty`: test that broadcast is NOT triggered when local results > 0
- [ ] Multi-platform user with/without `platformId` parameter
- [ ] Expiry job with ROAD mode and `delivered_at + 14 days` boundary

**Technical constraints:**
- [ ] Test framework: JUnit 5 + Mockito; no custom test frameworks

##### Integration tests

**Happy path:**
- [ ] eFTI platform client tests: REST vs eDelivery selection, subsetting, timeout, error handling
- [ ] Identifier repository tests: search filters at database level, role-based filtering
- [ ] Expiry job tests: 14-day expiry logic, ROAD mode only

##### E2E tests

**Happy path:**
- [ ] Gate-to-gate identifier request (between 2 gate instances)
- [ ] eFTI platform → eFTI Gate identifier save → Authority query → SSE stream (full happy path)
- [ ] Follow-up message forwarding — local and remote

**Technical artifacts:**
- [ ] CI: test coverage report published as artefact
- [ ] Test: subsetter with 10 MB XML, heap usage < 256 MB
