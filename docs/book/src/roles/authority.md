# Competent authority

> **Goal:** your information system can call the gate over X-Road, is recognised as an active
> authority with the right subsets, and can search, retrieve datasets and send follow-ups.

## Your role

A competent authority (police, customs, a transport inspectorate) looks for a consignment at the
roadside or in the back office. You **search** and **request** data. You never upload or store
freight data. The gate answers locally, asks peer gates when the data is abroad, and fetches the
dataset from the platform that holds it, filtered to the subsets you may see.

## Path

1. **Understand the interface.** [Competent authorities (X-Road)](../api/authorities/index.md):
   the call path, the headers, and the local test clients.
2. **Build against the mock first.** No Security Server, no registration needed:
   [Developer mock](../api/authorities/developer-mock.md), public at
   `https://eftidev.kemitaws.ee/developer/`. The mock's paths are the real paths with `/developer`
   instead of `/xroad/provide`, so you change only the base URL later.
3. **Get registered** (below).
4. **Verify the connection** with [echo](../api/authorities/echo.md) and
   [subsets](../api/authorities/subsets.md).
5. **Implement the tasks**, in the order an authority meets them:

| Task | Use case (with real requests) | Endpoint |
|---|---|---|
| Is this vehicle or container known? | [Check a transport means](../use-cases/authority-checks-transport-means.md) | [transport-means](../api/authorities/transport-means.md) |
| Which consignments match? | [Search for consignments](../use-cases/authority-searches.md) | [search](../api/authorities/search.md) |
| Show me the dataset | [Retrieve a dataset](../use-cases/authority-retrieves-dataset.md) | [dataset](../api/authorities/dataset.md) |
| Ask the platform a question | [Send a follow-up](../use-cases/authority-follow-up.md) | [follow-up](../api/authorities/follow-up.md) |

6. **Handle errors.** [Error catalogue](../data/errors.md) and
   [Conventions](../api/conventions.md).

## Get registered and connected

Three things must be true before a call succeeds. Until they are, every call returns `403`.

1. **The gate operator adds your organisation to `registry/authorities/<id>.yml`.** The entry holds
   your `registryCode` (the business-registry code, which is the `memberCode` of your X-Road client
   id) and the `subsets` you may request, for example `EU01`. An authority exists because its file
   exists: there is no status. See [Change the registry](./registry-changes.md) and
   [eFTI subsets](../data/subsets.md).
2. **Your X-Road subsystem is allowed to use the gate's service.** The gate is one X-Road OpenAPI
   service, code `efti`. Its provider URL is `<gate host>/xroad/provide`; consumers call it on their
   own Security Server as
   `POST /r1/<instance>/GOV/<gate member code>/efti-gate/efti/v1/<operation>`. Access rights on the
   Security Server are granted by the X-Road side, not by the gate. The service description to
   register is served publicly at `GET /xroad/provide/openapi`
   ([openapi](../api/authorities/openapi.md)).
3. **Your calls carry the right headers.** `X-Road-Client` (the credential, set by the Security
   Server) and `X-Road-Id`, a fresh UUID per call. Details: [Competent authorities
   (X-Road)](../api/authorities/index.md#headers).

The gate identifies you by `memberCode` only. The X-Road instance does not affect the lookup, and
two registry files with the same `registryCode` are denied as ambiguous.

## Things that catch integrators

- **Polling.** `search` and the `allgates` form of `transport-means` can answer before every peer
  gate has. The first response carries `x-search-id` and `x-poll-more: true`. Call again with
  `{"poll": true, "searchId": "<x-search-id>"}` and a **new** `X-Road-Id` until `x-poll-more` is
  `false`. See [search](../api/authorities/search.md).
- **A local hit skips the fan-out.** If your gate has a match it answers at once and does not ask
  peers (the search page explains when it does).
- **`X-Road-Id` must be unique** for `dataset` and `follow-up`; a repeat is `409`.
- **Peer-gate rows are thin.** A result from another country carries the UIL and the main transport
  id; other fields are empty. The dataset call returns the content.
- **Subsets filter the dataset.** A request for a subset you are not entitled to is `403
  FORBIDDEN_SUBSET`. See [Dataset content](../data/dataset-fields.md).
- **Do not call the gate's internal API.** `/efti/api/v1/authority/**` is for gate components and
  needs the internal service token. You call `/xroad/provide/**` only.

## Go-live checklist

1. The gate operator confirms your `registryCode` and `subsets` in the registry.
2. Your Security Server has access rights to the gate's `efti` service.
3. `echo` returns your authority row with the right `registryCode` and `subsets`.
4. `subsets` returns what you expect; cache it.
5. Each call uses a new UUID `X-Road-Id`.
6. Your client polls while `x-poll-more` is `true`.
7. Your client maps every error code in the [catalogue](../data/errors.md), including `502`/`500`
   from a failing platform or peer.

## More

- Run it yourself against two local gates: [Getting started](../getting-started/prerequisites.md).
- The same cases, with verified answers, in the repository:
  [`docs/test-cases/xroad-provide.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/test-cases/xroad-provide.md).
- X-Road design: [X-Road integration](../architecture/integrations/x_road_integration.md) and
  [ADR-006](../architecture/decisions/006-xroad-identity-and-subsets.md).
