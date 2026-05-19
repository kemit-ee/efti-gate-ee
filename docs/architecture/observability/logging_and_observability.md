# Architecture: Logging and Observability

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the Logging and Observability surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/observability/logging_and_observability.md`](../../cfr/observability/logging_and_observability.md).

## Log pipeline at a glance

```mermaid
flowchart LR
    Req[Inbound request<br/>X-Request-ID] --> MDC["Put http.request.id<br/>generated UUID if missing"]
    MDC --> Encoder[ECS JSON encoder]
    Encoder --> Stdout[Rolling JSON file<br/>or stdout]
    Stdout --> Aggregator[Log aggregator<br/>Filebeat/Fluentd → ES/OpenSearch]
    Aggregator --> Search[Searchable by http.request.id<br/>across all nodes]
    MDC -.cleared on response.- Req
```

## Rationale

ECS-shaped JSON gives the operator one consistent schema to index, search, and alert against (regardless of which log-aggregator stack they run). The `efti.*` namespace keeps custom fields from colliding with ECS evolution. Mandatory `http.request.id` plus per-call outbound logging lets an on-call engineer reconstruct an end-to-end trace across the local gate, eDelivery hop, and peer-gate without distributed tracing infrastructure.

