# Theme: Integrations

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Architecture: [`../../architecture/integrations/README.md`](../../architecture/integrations/README.md). The overarching rules are defined there; the AC below verify the gate honours those rules end-to-end.

<!-- issue-body:begin -->

**Objective:** Ensure the gate's interoperability at both EU level (eDelivery AS4) and Estonian national level (X-Road, ANTS, and competent authority information systems).

## Business value



## Acceptance Criteria

**Theme done when:**
- [ ] EPIC 10 (eDelivery AS4): inbound/outbound AS4 messages handled; async responses delivered
- [ ] EPIC 11 (X-Road, EE): platform registration available as X-Road service; core unchanged

<!-- issue-body:end -->

## Sub-areas

- [eDelivery AS4 Integration](edelivery_as4.md)
- [X-Road Integration (EE extension)](x_road_integration.md)
- [eDelivery AS4 Message Flow](as4_message_flow.md)
