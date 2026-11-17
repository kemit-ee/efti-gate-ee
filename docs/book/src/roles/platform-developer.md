# Freight platform

> **Goal:** your platform is registered at the gate, can upload consignment identifiers, and answers
> the gate when it asks for a dataset or delivers a follow-up.

## Your role

A freight platform holds the consignment datasets for transport operators. You **register the
identifiers** of a consignment with the national gate so that authorities can find it. You keep the
dataset and return it, filtered to the requested subsets, when the gate asks. The gate stores
identifiers only.

## Path

1. **Choose the transport.** REST with an API key is the simple path. AS4 suits a platform that
   already operates an eDelivery access point. Both are described side by side on
   [Freight platforms](../api/platforms/index.md).
2. **Be registered** (below).
3. **Upload identifiers.** [REST](../api/platforms/upload-rest.md) or
   [eDelivery](../api/platforms/upload-edelivery.md). A short form (`ParameterIDSetCriteria`) is
   enough to be found by transport identifier; the full `FTI004` carries more criteria.
4. **Implement the two calls the gate makes on you:** dataset and follow-up. The contract is on
   [What the gate calls on a platform](../api/platforms/platform-side-api.md).
5. **Test against the built-in mock** and see the complete flow in
   [Register a consignment](../use-cases/platform-registers-consignment.md).
6. **Know your errors:** [Error catalogue](../data/errors.md).

## Get registered

There is no API that creates a platform. The gate operator adds `registry/platforms/<id>.yml`
([Change the registry](./registry-changes.md)). Agree with the operator:

| Item | What you provide | What you receive |
|---|---|---|
| Platform `id` | A short ASCII id. It becomes the `platformId` of every UIL you own | Confirmation |
| `baseUrl` | The REST base URL the gate calls (`<baseUrl>/v1/dataset/…`). It must be reachable from the gate. HTTPS is recommended | |
| `headers` | Any header the gate must send with each call, for example your own API key | |
| `apiKey` | | The `X-Api-Key` value you send to the gate, passed to you out of band |
| `eDeliveryCert` | AS4 only: your public certificate | The gate's certificate |

Your key is rotated by changing the file and re-running the registry build; there is no endpoint
that issues keys.

## What you implement

| Direction | Call | Page |
|---|---|---|
| You → gate | `POST /platforms/v1/consignments[/{datasetId}]` with `X-Api-Key` | [Upload (REST)](../api/platforms/upload-rest.md) |
| Gate → you | `GET <baseUrl>/v1/dataset/{datasetId}?subsets=EU01,EU02` returns the FTI010 element | [Platform side](../api/platforms/platform-side-api.md) |
| Gate → you | `POST <baseUrl>/v1/dataset/{datasetId}/follow-up` | [Platform side](../api/platforms/platform-side-api.md) |

Rules that matter:

- **You filter by subset.** The gate does not filter your answer. Return only what the requested
  subsets allow ([eFTI subsets](../data/subsets.md), [Dataset content](../data/dataset-fields.md)).
- **A new version is a repeated upload** of the same `datasetId`. The latest wins; earlier versions
  are kept.
- **The `datasetId` is a UUID** and part of the UIL (`gateId`, `platformId`, `datasetId`). See
  [UIL](../data/uil.md).
- **A `DISABLED` platform stops working entirely.** The key no longer authenticates and requests
  for its datasets answer "Platform Not Found".
- **Full FTI004:** the `GateID` must be this gate and the `PlatformID` your own id, otherwise `403`.

## Try it without a real platform

The gate contains a [mock platform](../api/platforms/mock-platform.md) that implements the two
calls above with canned data. The sample upload bodies are in the repository:
[`docs/developer/examples/platforms/`](https://github.com/kemit-ee/efti-gate-ee/tree/dev/docs/developer/examples/platforms).
The local two-gate network and the [Postman collection](../reference/collection.md) run all of it.

## Go-live checklist

1. Platform id, `baseUrl`, headers and key are agreed and in the registry of the target gate.
2. Your upload with the test key succeeds (`201`) and an authority search finds it.
3. `GET /v1/dataset/{id}` returns valid FTI010 for each subset combination and honours the filter.
4. `POST /v1/dataset/{id}/follow-up` accepts a follow-up message.
5. You answer a request for a dataset you do not know with a clear status; the gate passes your
   status and body on.
6. You received the production key over a secure channel. Never reuse the test key.
