# ADR-007: `transport-means` `scope: allgates` response shape

**DECISION (deciders unspecified, 11.09.2026): option A — the `xml-mapper` normalisation endpoint.**

> **Change (11.09.2026, decision + implementation, issue #125):** the choice fell on **option A**.
> Two facts that emerged in the meantime sharpened the choice:
>
> 1. `core`'s `authority/search.yml` is now **local-first and non-blocking** (ADR-010):
>    a local hit responds immediately with `x-poll-more: false`; on a local miss a
>    broadcast query is registered in the background and the first response is always `found: 0` +
>    `x-poll-more: true` — remote results come only by polling. This means option B would have
>    returned **two different shapes** inside `allgates` — the raw `get_consignments` rows of a
>    local hit (with the `xml` blob) vs the `ConsignmentRow[]` of polled remote results — i.e.
>    worse than this ADR assumed when it described B.
> 2. The normaliser's input is `core` search's **JSON output**, not XML: by that moment the remote
>    XML has already gone through `search/response-to-json`. The new endpoint
>    `POST /api/v1/transport-means/normalize` takes both shapes (the klite JSON parser ignores
>    unknown keys) and emits the same curated projection that
>    `get_consignments_by_transport_means.sql` produces — `uil` nested, without `xml` and
>    seq arrays. Remote rows have no `createdAt`.
>
> A related decision in the same implementation: `get_consignments.sql` got a new criterion
> `transportMeansOrEquipmentId` (OR across three identifier families), which `xml-mapper`
> **degrades** to `MainCarriageTransportMeansIDParameterScope` for the broadcast-query XML —
> FTI019 has no OR criterion, so equipment ids match only in the local registry.
>
> Option D (DataMapper) remains a future possibility via ADR-008; if ADR-008 is ratified, the
> normalisation can be moved there without changing the contract — the external shape is the same.

Related: [issue #125](https://github.com/kemit-ee/efti-gate-ee/issues/125), [ADR-006](006-xroad-identity-and-subsets.md),
[ADR-008](008-adopt-datamapper.md) (option D depends on it), PR #121.

## Context

`POST /xroad/provide/v1/transport-means` gets a graded `scope` parameter (PR #121 comment, point 3):

| `scope` | Extent | Response | Latency |
|---|---|---|---|
| `existence` | local | `{ identifier, countryCode, registered }` | p95 < 1 s |
| `local` *(default)* | local | `{ identifier, countryCode, found, consignments: [...] }` | synchronous |
| `allgates` | local + neighbouring gates | *(this ADR)* | asynchronous, `x-poll-more` + polling |

The central promise of the `scope` design: **the `local` and `allgates` response shapes are
identical**, so that involving the network is a one-field change for the client, not another
integration.

`scope: local` returns the **curated projection** of
`DSL/Resql/efti/POST/get_consignments_by_transport_means.sql`: `uil` as a `jsonb_build_object`,
~20 named fields, **no `xml` blob**.

`scope: allgates` must also bring results from neighbouring gates. Those go through `core`'s
`efti/POST/api/v1/authority/search`, which calls the multiplexer and gets the response **as X-Road
search-response XML**, which `xml-mapper`'s `POST /api/v1/search/response-to-json` converts to JSON.

**`core`'s `authority/search.yml` does not itself give a consistent shape:**
- a local hit (`respond_local`) → the **raw rows** of `get_consignments.sql` (`row_id`, `xml`,
  `status::text`, snake_case)
- a remote hit (`respond_first` / `respond_rest`) → the output of `xml-mapper`'s
  `search/response-to-json`

So the `allgates` branch must unify the shape itself if the promise is to hold.

## Options

### Option A — a new `xml-mapper` conversion endpoint

A new `xml-mapper` endpoint (e.g. `POST /api/v1/transport-means/response-to-json`, Kotlin,
`code/xml-mapper/`) that emits the **same curated projection shape** from the X-Road
search-response XML. The `allgates` branch of `transport-means.yml`:

1. local rows: the existing `get_consignments_by_transport_means.sql` projection
2. remote XML collected via `core` search → the new `xml-mapper` endpoint → the same projection
3. merges both into the `consignments` array, `found` = length, `x-poll-more` forwarded

**For:**
- the `scope` promise holds — `local` ↔ `allgates` response shape identical, one mental model
- the `existence` semantics stay coherent (all three `scope` values speak the same language)
- the external consumer (ANTS / NES) gets a stable field contract regardless of how many gates
  responded

**Against:**
- Kotlin work in `xml-mapper`; a new conversion to maintain against the eFTI search-response XSD
- one more moving part in the conversion chain
- `xml-mapper` currently has no concept of a transport-means-level projection — this is a new
  responsibility

### Option B — loosen the promise

`allgates` returns results **in `core` search's JSON shape** (not the curated projection). The
response shape **differs** between `local` and `allgates`; document it in `openapi.yaml` as a
separate schema (`TransportMeansAllgatesResponse` vs `TransportMeansLocalResponse`).

The `allgates` branch of `transport-means.yml` = essentially the existing
`DSL/Ruuter-xroad/xroad/POST/provide/v1/search.yml` (forward to `core`, forward `x-poll-more`,
`core_error` → 502), plus the EU02 check and the fixed `transportMeansOrEquipmentId` criterion.

**For:**
- minimal — ~40 lines of DSL, a repeat of the existing `search.yml` pattern, **zero Kotlin work**
- does not add a new responsibility to `xml-mapper`
- the fastest path to `allgates` functionality

**Against:**
- the elegance of the `scope` design suffers: a client going from `local` to `allgates` must
  handle two response shapes
- the `found` / `consignments` field contract differs; the "one endpoint, one shape" promise breaks
- the `xml` blob may come along in remote results (search's shape includes it), which conflicts
  with the "identifier level only, no dataset content" principle of `transport-means` — needs
  separate filtering or a clear warning

### Option D — a DataMapper template, unified for local and remote

Depends on [ADR-008](008-adopt-datamapper.md) (adopting DataMapper). One Handlebars template in
`DSL/DMapper/hbs/`, run **on both the local ReSql rows and the remote search results** → one
curated shape for both, regardless of source.

`transport-means.yml`:
1. local rows: `get_consignments_by_transport_means.sql` (or the simpler `get_consignments.sql`) →
   the DataMapper `transport_means` template
2. remote results: `core` search → `xml-mapper`'s `search/response-to-json` → the **same** DataMapper
   template
3. merges both, `found` = length, `x-poll-more` forwarded

**For:**
- the `scope` promise holds fully — `existence` / `local` / `allgates` speak the same language
- the projection is a declarative, database-free testable template — not Kotlin or
  `jsonb_build_object`
- adds no responsibility to `xml-mapper`; the layering gets clearer (see ADR-008)
- the pattern repeats for every future `allgates`-type route

**Against:**
- requires ratifying ADR-008 — a new service in `compose.yml`, the `DSL/DMapper/` structure,
  a learning curve. This is a decision about the whole response-shaping strategy, not just ADR-007's.
- if ADR-008 is deferred, this option is deferred too

## Why option C (DSL-side reshaping) does not fly at all

A third conceivable approach — leaving the remote results in `core` search's shape and
**reshaping them in `transport-means.yml` itself** into the curated projection — is not a real
option:

1. **The Ruuter DSL engine does not provably support array transformation.** `.map()`,
   `Array.isArray`, arrow functions — none of them is used in any DSL file in this repo, and
   **the whole of PR #121 is built around this limitation** (documented in every one of its files:
   the declaration comment of `dataset.yml`, the `check_input` / `check_lookup` comments of
   `vehicle.yml`, the header of `check_authority_subsets.sql`). Reshaping an array element-by-element
   requires exactly those constructs.

2. **Even if the engine supported it:** the remote results come from the multiplexer as XML, which
   `xml-mapper`'s general `search/response-to-json` turns into JSON. To reshape in the DSL, the
   exact schema of that JSON would have to be known and ~20 fields per row mapped by hand in YAML —
   unmaintainable and fragile to changes in `xml-mapper`'s output.

3. **Therefore:** reshaping must happen either in SQL (covers only local rows — does not solve
   `allgates`) or in `xml-mapper` (**that is option A**). "Do it in the DSL" is not an approach
   allowed in the codebase, and option C reduces to it.

## Recommendation

- **Option D**, if [ADR-008](008-adopt-datamapper.md) goes through — it is the only option that
  fully keeps the `scope` promise, without Kotlin work, and scales to the next routes. ADR-007 and
  ADR-008 are tightly linked through this choice.
- **Option A**, if DataMapper is not adopted but the curated field contract matters to the external
  consumer (ANTS / NES expects a stable schema).
- **Option B** with a clear `openapi.yaml` note, if `allgates` is a rare use for now and fast
  delivery outweighs the badness of two shapes.

> **Change (11.09.2026):** decision made — **option A** (see the header Change block). ADR-008 was
> unratified at the time of implementation, so option D was not available; the curated field contract
> matters to the external consumer (ANTS / NES expects a stable schema), which ruled out B. The
> deciders are so far "unspecified" in the header — confirmation by Sten Viljus + the architects is
> pending; on confirmation, update the header and the README row.

## Consequences

**Option A:**
- a new `xml-mapper` endpoint + tests; a dependency on the eFTI search-response XSD
- `transport-means.yml` calls the same projection logic on local and remote results
- `openapi.yaml`: one `transport-means` 200 schema, `x-poll-more` header added

**Option B:**
- the `allgates` branch of `transport-means.yml` mirrors `xroad/POST/provide/v1/search.yml`
- `openapi.yaml`: two 200 schemas by `scope`; a separate note in the `scope` table of
  `developer/x_road_authority_integration_guide.md` that the `allgates` shape differs
- the client must know `core` search's field contract for `allgates`

**Option D:**
- the consequences of ADR-008 (a new service, the `DSL/DMapper/` structure)
- `DSL/DMapper/hbs/transport_means.hbs` — one projection template
- `transport-means.yml` calls the template on both local and remote results
- `openapi.yaml`: one `transport-means` 200 schema for all `scope` values

## Applied changeset

Implemented as part of issue [#125](https://github.com/kemit-ee/efti-gate-ee/issues/125) (option A):

- `code/xml-mapper/src/efti/TransportMeansRoutes.kt` + `efti/domain/TransportMeansConsignment.kt` —
  `POST /api/v1/transport-means/normalize` (both `core` search shapes → the curated projection)
- `code/xml-mapper/src/efti/xml/fti/ParameterSearchCriteria.kt` — the `transportMeansOrEquipmentId`
  criterion + degradation to `MainCarriageTransportMeansIDParameterScope` in the broadcast-query XML
- `DSL/Resql/efti/POST/get_consignments.sql` — `transportMeansOrEquipmentId` (`EQ`/`NE`), OR across
  three identifier families
- `DSL/Ruuter-xroad/xroad/POST/provide/v1/transport-means.yml` — the `allgates` branch
  (forward → normalize → respond, `x-poll-more` forwarded) + polling (`{poll: true}` with the same
  `X-Road-Id`, `x-poll` header towards `core`)
- `docs/specs/openapi.yaml`, `docs/developer/x_road_openapi.yaml`,
  `docs/developer/x_road_authority_integration_guide.md`, ADR-006 — the contract and the limitations
- `tests/authority/xroad-transport-means.http` — `allgates` + polling scenarios
