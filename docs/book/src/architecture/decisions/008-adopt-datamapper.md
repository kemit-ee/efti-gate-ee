# ADR-008: Adopting DataMapper for response shaping

**DRAFT (deciders unspecified, 03.09.2026).**

Tightly linked to [ADR-007](007-xroad-transport-means-response-shape.md) — ADR-007's option
"D" depends on this decision, and this ADR was prompted by ADR-007's need. If one is deferred,
the other must be reviewed.

> **Change (11.09.2026):** ADR-007 was decided without this one (option A), because this ADR was
> unratified. As a result `xml-mapper` now has one JSON→JSON shaping endpoint
> (`POST /api/v1/transport-means/normalize`) — exactly the responsibility-mixing argued against
> below. A deliberate interim phase: if this ADR is ratified, `/normalize` is the first candidate
> to move into DataMapper; the external contract does not change.

## Decision

We adopt the **Ruuter/Bürokratt DataMapper** (a Node.js service, Handlebars templates in
`DSL/DMapper/`) as the place where Ruuter shapes a **JSON response into the API-contract shape**.
Ruuter calls it with `http.post` like any other service (a new `DMAPPER_URL` constant, a service
in `compose.yml`).

**Division of work:**

| Service | Responsibility |
|---|---|
| **ReSql** | data query — reads rows, does not shape the API contract |
| **`xml-mapper` (Kotlin)** | XML ↔ JSON against the eFTI XSD — schema-validating, complex domain logic (subsets, DG, transport modes) |
| **DataMapper (new)** | JSON → JSON — response shape, field naming, array projection, error-body normalisation |

## Rationale

### 1. The ADR-007 prompt — the direct reason

`transport-means` `scope: allgates` needs **a unified shape for local and remote results**. Without
DataMapper, ADR-007's choice is poor:
- **option A** — a new Kotlin endpoint in `xml-mapper` just for this projection; or
- **option B** — give up the "one endpoint, one shape" promise.

With DataMapper there is **option D**: one Handlebars template, run **on both the local ReSql rows
and the remote search results** → truly one projection, one shape, zero Kotlin work. The same
pattern repeats for every future `allgates`-type route.

### 2. Response shaping is currently scattered and implicit

- `get_consignments_by_vehicle.sql` builds the curated projection by hand with `jsonb_build_object`
  — the SQL does a data query *and* the API contract at once.
- `get_consignments.sql` returns raw rows in `SELECT *` shape — a second shape over the same domain.
- Remote search results come from `xml-mapper`'s `search/response-to-json` — a third shape.

Three shapes for one concept (shipment identifier data). DataMapper gives **one place** where the
shape is defined — a readable, database-free testable, versioned template.

### 3. Ruuter DSL's JS limitations stop being a blocker

The Ruuter expression engine has no proven support for `.map()` / `Array.isArray` / arrow functions
— **the whole of PR #121 is built around this limitation** (documented in every one of its files).
Reshaping an array element-by-element in the DSL is not an option. DataMapper (Handlebars +
`DSL/DMapper/lib/` JS helpers) is made exactly for this — the limitation disappears from response
shaping, the DSL stays thin routing and guard logic.

### 4. Alignment with the reference stack

`docs/planning/rest-api-disainijuhend.md` §2.5's data flow **already assumes DataMapper**
(`R->>M: map_user { users }` → `M-->>R: { mapped user object }`). The current absence is a deviation
from the design guide, not a deliberate simplification. Adopting it brings the code in line with the
guide.

### 5. Reuse across the gate

- assembling audit-log records (if the audit story of [ADR-006](006-xroad-identity-and-subsets.md)
  materialises) — composing an event body from rows
- normalising error bodies into a uniform RFC 7807 shape
- assembling the `follow_up_log` payload
- shaping admin-UI list responses (the design guide's `map_user`)

## Alternatives considered

- **Extend `xml-mapper` (Kotlin).** Works, but every new response shape = a Kotlin change + image
  build + deploy. `xml-mapper`'s purpose is XSD-driven XML work with complex domain logic, not
  arbitrary JSON→JSON shaping — mixing the two inflates the service's responsibility.
- **Stay with SQL (`jsonb_build_object`).** Scales poorly to nested shapes and array transformations
  (e.g. `allgates` merges two different sources), and couples the data query to the API contract —
  changing SQL for the sake of API shape is a change at the wrong layer.
- **Ruuter's `template:` step.** Ruuter has a `template` step, but it runs another DSL handler as a
  subroutine (and bypasses the guard — `rest-api-disainijuhend.md` §§208–209); it is not a
  structural JSON transformation engine.
- **Do nothing (option B).** ADR-007 is solved by giving up the promise. Cheap today, but every next
  route that wants a curated response across multiple sources hits the same wall.

## Consequences

**Positive:**
- ADR-007 is solved with option D — one shape, a declarative template
- a clear layering: ReSql = data, `xml-mapper` = XSD-XML, DataMapper = JSON shape
- the DSL's JS limitations are no longer a blocker for response shaping

**Cost:**
- **a new service in `compose.yml`** (Node.js) — a deployment unit, monitoring, a health probe, a
  version
- **the `DSL/DMapper/` directory structure** — `hbs/` templates, `lib/` helpers, a template test
  harness
- **a learning curve** — Handlebars conventions, the DataMapper calling pattern from Ruuter
- **migration is incremental** — existing SQL projections can be left in place; new ones and the
  `allgates` path go into DataMapper. A full migration is separate work, not the scope of this ADR.
- `pikker-deploy.sh` / `compose.pikker.yml` need the new service added

## Applied changeset

1. `compose.yml` + `compose.pikker.yml`: a `datamapper` service
2. `constants.ini`: `DMAPPER_URL=http://datamapper:3000` (or similar)
3. `DSL/DMapper/hbs/`, `DSL/DMapper/lib/` — the initial structure + the first template
   (the `transport_means` projection for ADR-007)
4. `docs/architecture/` — the layering documented
5. ADR-007: DRAFT → DECISION as option D, if this ADR is ratified

See also [issue #125](https://github.com/kemit-ee/efti-gate-ee/issues/125).
