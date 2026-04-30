# EPIC 16 — Logging and Observability

> Part of [Theme 7](theme_7_en.md)

**AS A** operations engineer  
**I WANT** structured JSON logs, request tracing, and operational visibility  
**SO THAT** I can troubleshoot issues, monitor performance, and ensure GDPR compliance

**Reference:** [Logging Specification](../specs/logging-spec.md) — Complete logging format, ECS schema, and audit trail specification

#### Acceptance Criteria

##### Structured logging

**Happy path:**
- [ ] All log lines in JSON conforming to Elastic Common Schema (ECS)
- [ ] Mandatory fields: `@timestamp`, `log.level`, `trace.id` (requestId), `service.name`, `user.id`, `url.path`, `client.ip`, `http.response.status_code`, `event.duration`
- [ ] JSON/text format switchable via `LOG_FORMAT=json|text`

**Edge cases:**
- [ ] `user.id` not available (unauthenticated request) → field set to `"anonymous"`; not omitted
- [ ] `event.duration` not calculable (connection dropped) → field set to `-1`; not omitted

**Technical constraints:**
- [ ] MUST use Logback or Log4j2 with ECS encoder — no custom JSON formatting

##### Request ID propagation

**Happy path:**
- [ ] `X-Request-ID` header added to MDC at start of each request
- [ ] All log lines for same request contain same `trace.id` value
- [ ] Log context cleared at end of request (thread safety)

**Edge cases:**
- [ ] Inbound request without `X-Request-ID` → gate generates UUID and uses it; logs it as `generated=true`

##### Outbound request logging

**Happy path:**
- [ ] Gate-to-gate client logs each gate called: gate ID, protocol, URL, duration ms, HTTP status, error (if any)
- [ ] eDelivery client logs: recipient Party ID, requestId, duration ms, response status
- [ ] eFTI platform client logs REST and eDelivery: platform ID, URL, duration ms, HTTP status

**Edge cases:**
- [ ] Outbound request times out → logged at WARN with gate ID and configured timeout value

##### Business logic logging

**Happy path:**
- [ ] Identifier search logs: local result count, broadcast gate count, result per gate, `broadcastTriggered: true/false`
- [ ] Dataset request logs: UIL, routing decision (local vs remote), duration ms
- [ ] Authorisation denials: user ID, endpoint, reason for denial

**Technical artifacts:**
- [ ] Logging configuration: `logback-spring.xml` with ECS encoder
- [ ] Environment variable: `LOG_FORMAT=json|text`
