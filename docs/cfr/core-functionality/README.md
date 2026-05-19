# Theme: Core Functionality

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Architecture: [`../../architecture/core-functionality/README.md`](../../architecture/core-functionality/README.md). The overarching rules are defined there; the AC below verify the gate honours those rules end-to-end.

<!-- issue-body:begin -->

**Objective:** Implement the eFTI Gate's four core functions in accordance with EU Regulations 2020/1056 and 2024/1942: identifier registration (Platform), search (Authority), dataset retrieval by UIL, and follow-up message forwarding.

## Business value



## Acceptance Criteria

**Theme done when:**
- [ ] EPIC 3 (Identifier registration): platforms can register/update identifiers via REST
- [ ] EPIC 4 (Identifier search): local + broadcast search works, SSE streaming complete
- [ ] EPIC 5 (Dataset + follow-up): UIL-based dataset retrieval and follow-up forwarding works

<!-- issue-body:end -->

## Sub-areas

- [Identifier Management (Platform API)](identifier_management.md)
- [Identifier Search (Authority API)](identifier_search.md)
- [Dataset Retrieval and Follow-up](dataset_retrieval_and_follow_up.md)
- [Identifier Search and Dataset Retrieval Flows](search_and_retrieval_flows.md)
