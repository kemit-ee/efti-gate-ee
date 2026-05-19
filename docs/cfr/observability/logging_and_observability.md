# EPIC 16 — Logging and Observability

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: Observability](README.md). Architecture: [observability/README.md](../../architecture/observability/README.md) (theme-wide rules) + [observability/logging_and_observability.md](../../architecture/observability/logging_and_observability.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** operations engineer
**I WANT** structured JSON logs, request tracing, and operational visibility
**SO THAT** I can troubleshoot issues, monitor performance, and ensure GDPR compliance.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **Output format** | JSON, single-line, ECS 8.x dotted-field taxonomy, `efti.*` namespace for custom fields: [`non-functional.md`](../../specs/non-functional.md) §4 |
| **Field taxonomy** | Mandatory fields (`@timestamp`, `log.level`, `message`, `service.name`, `service.version`, `host.hostname`, `http.request.id`); ECS event lifecycle; event.action / event.outcome / event.duration; full `efti.*` field reference: [`logging-spec.md`](../../specs/logging-spec.md) §2 |
| **Pipeline** | Application → async log queue (non-dropping, default size 512) → rolling JSON file → operator-supplied log aggregator: [`logging-spec.md`](../../specs/logging-spec.md) §1.1 |
| **Levels and retention** | ERROR / WARN / INFO / DEBUG / TRACE policy, per-class retention: [`logging-spec.md`](../../specs/logging-spec.md) §3, §8 |
| **Environment** | `LOG_LEVEL`, `LOG_FORMAT=JSON|TEXT`, `LOG_FILE_PATH`, `LOG_DATASET_CONTENT`, `LOG_SAMPLING_RATE`, `LOG_ASYNC_QUEUE_SIZE`: [`logging-spec.md`](../../specs/logging-spec.md) §10 |
| **Architecture** | [../../architecture/observability/README.md](../../architecture/observability/README.md) (theme rules) + [../../architecture/observability/logging_and_observability.md](../../architecture/observability/logging_and_observability.md) (sub-architecture) |

## Acceptance Criteria

### Structured logging

**Business rules:**
- [ ] Every log line is single-line JSON conforming to ECS 8.x.
- [ ] Required fields on **every** entry: `@timestamp`, `log.level`, `message`, `service.name`, `service.version`, `host.hostname`. Request-scope entries also carry `http.request.id`.
- [ ] Custom fields use the `efti.*` namespace; never put custom fields at the ECS root.
- [ ] Format switchable via `LOG_FORMAT=JSON|TEXT` (JSON in production; TEXT for local development only).
- [ ] `user.id` unset on unauthenticated requests → field present with value `"anonymous"` (never omitted).
- [ ] `event.duration` not calculable (connection dropped before response) → field present with value `-1` (never omitted).

### Request-id propagation

**Business rules:**
- [ ] `X-Request-ID` is captured into the logging context at the start of every request and emitted as `http.request.id` on every entry generated during request processing.
- [ ] Inbound request without `X-Request-ID` → the gate generates a UUID v4 and uses it; flagged via an additional field (e.g. `efti.request_id.generated: true`).
- [ ] Logging context is cleared on response, so requests do not leak `http.request.id` into each other under thread / coroutine reuse.

### Outbound and business-event logging

**Business rules (one log entry per outbound call):**
- [ ] Gate-to-gate client: `event.action: "g2g.*"` carrying target `efti.gate.id`, chosen protocol (`fast` / `eDelivery`), URL, duration, HTTP / SOAP status, error (if any).
- [ ] eDelivery client: recipient Party ID, `requestId`, duration, response status.
- [ ] Platform client (REST and AS4): `efti.platform.id`, URL, duration, HTTP status.
- [ ] Outbound timeout → entry at WARN carrying the target id and the configured timeout value.

**Business-event entries (mandatory):**
- [ ] Identifier search: `event.action: "identifier.search"` with `efti.search.local_count`, `efti.search.broadcast_triggered`, `efti.search.gates_queried`, `efti.search.failed_gates`.
- [ ] Dataset request: `event.action: "dataset.deliver"` with the UIL components, routing decision (local vs remote), duration.
- [ ] Authorisation denials: `event.action: "user.access.denied"` with caller `user.id`, endpoint, denial reason.

<!-- issue-body:end -->
