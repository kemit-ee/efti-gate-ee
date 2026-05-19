# Architecture: API Standardisation

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the API Standardisation surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/software-quality/api_standardisation.md`](../../cfr/software-quality/api_standardisation.md).

## Request handling at a glance

```mermaid
flowchart TD
    Req[Request to /api/v1/* or /v1/*] --> CORS[CORS check<br/>ALLOWED_ORIGINS or same-origin]
    CORS --> Ver{Version supported?}
    Ver -- deprecated --> Dep[200 OK<br/>Deprecation: true header]
    Ver -- current --> Schema{OpenAPI 3.0 schema valid?}
    Ver -- unsupported --> R410[410 Gone]
    Schema -- no --> R400[400 Bad Request<br/>RFC 7807 field errors]
    Schema -- yes --> Handler[Resource handler]
    Handler --> Page[Paginate: limit, offset,<br/>X-Total-Count]
    Handler --> Err[Error → RFC 7807<br/>type, title, status, detail, requestId]
```

## Rationale

A single OpenAPI document is the contract every integration partner reads first. The gate's API surface is wide (Platform / Authority / Admin / eDelivery / health); RFC 7807 + `requestId` give every error a uniform shape and a correlation hook. Versioning by URL prefix is the simplest path-prefix choice that matches the access-check rules in `permissions-matrix.md`; 6-month deprecation windows give integration partners predictable migration time.

