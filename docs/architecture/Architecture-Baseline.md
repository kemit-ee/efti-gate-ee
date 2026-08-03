# Estonian eFTI Gate — Architecture Plan

## Components

### Ruuter (REST Gateway)
Routes all incoming REST requests using YAML DSL files. Each DSL file defines a workflow of steps: HTTP calls, data transformation (JS `${...}` expressions), conditional branching, and return values.

**How it works:**
- URL path maps to filesystem: `POST /api/save` → `DSL/POST/api/save.yml`
- DSL steps execute sequentially; each step can call HTTP endpoints, transform data, or invoke other DSLs
- Request data available as `${incoming.body}`, `${incoming.params}`, `${incoming.headers}`
- Step results stored by name: `result: myData` → `${myData.response.body}`

**Example DSL (proxy to ReSql):**
```yaml
query_dataset:
  call: http.post
  args:
    url: "http://resql:8080/query/dataset-by-uil"
    body:
      uil: ${incoming.body.uil}
  result: dataset

return_result:
  return: ${dataset.response.body}
```

### ReSql (Database Query Service)
Exposes prewritten `.sql` files as REST endpoints. Each `.sql` file becomes a `POST /query/{filename}` endpoint that accepts JSON parameters and returns query results as JSON.

**How it works:**
- `.sql` files placed in queries directory (e.g. `queries/dataset-by-uil.sql`)
- SQL uses named parameters: `:uil`, `:datasetId` (bound from request body)
- Returns JSON array of rows

**Example `.sql` file:**
```sql
-- queries/dataset-by-uil.sql
SELECT id, uil, status, created_at
FROM datasets
WHERE uil = :uil
```

**Called from Ruuter as:** `POST http://resql:8080/query/dataset-by-uil` with body `{"uil": "..."}`

### eFTI specific components
Handles eFTI/eDelivery protocol specifics that Ruuter cannot do:

XML-MAPPER:
| **XML/XSD parsing** | Parses incoming eFTI XML requests (by default only new XSD schemas), extracts data into JSON for Ruuter/ReSql                   |
| **XML generation** | Builds eFTI XML responses from JSON data returned by ReSql                                                                      |

multiplexer:
| **Multiplexing** | Fans out identifier queries to all remote gates, aggregates responses (returns first result immediately, full results on retry) |

EDELIVERY:
| **eDelivery protocol** | Handles AS4 messaging, SOAP envelopes, and eDelivery transport for gate-to-gate and platform communication                      |
| **Protocol translation** | Converts between REST (internal) and eDelivery/SOAP (external)                                                                  |

**All these components are called by Ruuter DSLs as HTTP services:**
```
Ruuter DSL step → POST http://edelivery:8080/parse/xml  (incoming XML → JSON)
Ruuter DSL step → POST http://edelivery:8080/build/xml  (JSON → outgoing XML)
Ruuter DSL step → POST http://edelivery:8080/send/gate  (deliver to remote gate via eDelivery)
```

## Data Flow Pattern (3-hop rule)

All database queries follow the same 3-hop pattern:

```
Client → Ruuter → ReSql → DB
         (route)  (query)
```

For operations requiring XML processing or external delivery, edelivery adds additional hops:

```
Client → Ruuter → edelivery (parse XML) → Ruuter → ReSql → DB
         (route)   (extract data)    (route)  (query)
```

## Query Implementation Reference

### Admin API (REST)

**Flow:** `UI → Ruuter → ReSql → DB`

All admin operations are pure REST-to-DB through Ruuter:
- Logical CRUD operations for gates, platforms, certificates (CR in DB)
- Each endpoint is a Ruuter DSL that calls ReSql for DB operations
- Auth via TIM/TARA with role-based access control

**Config change notification:** After DB write
CronManager iga päev (vms) genereerib constants.ini uuesti ja toimub redeploy

### Authority API (XTee)

#### Identifier Query (eeldab, et vastus on kindlas formaadis json)
```
Turvaserver → Ruuter → ReSql (local DB lookup)
            → multiplexer (multiplex query to all remote gates)
            → Ruuter → response
```
- Ruuter
- ReSql (local identifiers query)
  - If found -> Ruuter tagastab jsonit küsijale (Authority)
  - If not found -> xml-mapper -> multiplexer -> Send ~27 parallel queries through edelivery component -> Ruuter -> xml-mapper -> json response

- Full results available on retry (all gates respond) - JSON response

#### Dataset Query / Followup Query
```
XTR → Ruuter → ReSql (resolve UIL → platform/gate)
              → edelivery (forward to remote gate) OR platform (direct REST/eDelivery)
```
- ReSql looks up UIL to determine: local platform, remote gate, or both
- If local: direct REST call to platform
- If local eDelivery: send to platform via edelivery
- If remote: send to another gate via edelivery

Response: original XML sent by (local or remote) platform

### Platform Save Identifiers (FTI004)

**Flow:** `Platform → Ruuter → xml-mapper (parse XML) → Ruuter → ReSql → DB`

Consignment table should contain a separate column per each EFTI ParameterIDSetCriteria xml field.

Implementation steps in Ruuter DSL:
- **Receive XML** — xml-mapper parses `saveIdentifiersRequest` XML → JSON
- **Insert updated consignment** — ReSql: `INSERT ...`

### Gate-to-Gate Identifier Query (FTI019)

```
Remote gate → edelivery (receive) → xml-mapper (parse) → Ruuter → ReSql (local search) → edelivery (aggregate + respond)
```
- edelivery handles incoming eDelivery message
- ReSql searches local identifiers
- edelivery combines results and builds XML response

### Gate-to-Gate Dataset/Followup Query (FTI009/FTI025)

```
Remote gate → edelivery (receive) → xml-mapper (parse) → Ruuter → ReSql (resolve routing)
            → Platform (REST) or edelivery (forward to another gate)
```

## Implementation Notes (Rainer's requirements)

- **All REST traffic** goes through Ruuter — no direct component-to-component REST calls
- **All DB queries** go through ReSql — no direct database access from other components
- **edelivery handles protocol boundaries** — XML↔JSON conversion, eDelivery transport, gate multiplexing
- **Ruuter DSL files** define the orchestration logic; ReSql `.sql` files define the data access
- **Secrets** come from the secrets vault (except public certificates, which are in the DB)
- **Gate/Platform registry** (public certs, URLs) is stored in the database and cached by each edelivery instance

- Rainer verifies `[#${platformId}_URL]` dynamic syntax works in Ruuter
