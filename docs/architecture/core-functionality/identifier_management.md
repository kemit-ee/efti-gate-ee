# Architecture: Identifier Management (Platform API)

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the Identifier Management (Platform API) surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/core-functionality/identifier_management.md`](../../cfr/core-functionality/identifier_management.md).

## Registration flow at a glance

```mermaid
sequenceDiagram
    participant Platform
    participant Gate as eFTI Gate
    participant DB as PostgreSQL
    Platform->>Gate: POST /v1/identifiers/{datasetId}<br/>Client cert (mTLS, eDelivery AP)<br/>Content-Type: application/xml<br/>X-Request-ID: <uuid>
    Gate->>Gate: Resolve platform_id from active platforms by (cert_subject, cert_serial)<br/>Validate XSD (consignment-identifier.xsd)<br/>Check X-Request-ID dedup (10-min TTL)
    alt cert resolved + XSD valid
        Gate->>DB: INSERT consignments + identifiers<br/>(append-only: previous row stays in place but is no longer latest)
        Gate-->>Platform: 200 OK
    else duplicate X-Request-ID within TTL
        Gate-->>Platform: 409 Conflict<br/>code: DUPLICATE_REQUEST_ID
    else XSD invalid
        Gate-->>Platform: 400 Bad Request<br/>code: INVALID_XML
    end
```

## Rationale

The gate is a registry, not a store of dataset content. The platform owns the dataset; the gate keeps the identifiers + the minimal denormalised search columns. Append-only INSERTs preserve every state transition without UPDATEs, which is critical for the EU Reg 2024/1942 audit trail and the GDPR Art. 30 record of processing.

