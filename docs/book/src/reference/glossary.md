# Glossary

Terms as this book uses them. Where a term has a page of its own, the entry links to it.

## Parties

| Term | Meaning |
|---|---|
| **eFTI** | Electronic Freight Transport Information, Regulation (EU) 2020/1056 |
| **Gate** | A member state's node that stores consignment identifiers and routes requests. "This gate" is the Estonian one (EE). The local network has demo gates G1 and G2 |
| **Peer gate** | Another member state's gate |
| **Freight platform** | A certified system that holds the consignment datasets and uploads identifiers to the gate |
| **Competent authority** | An inspecting body (police, customs, a transport inspectorate) that searches and requests datasets |
| **Administrator** | A person with an active user row who may use the admin UI and API. There are no roles |

## Data

| Term | Meaning |
|---|---|
| **Consignment** | A shipment. At the gate it is an identifier record, not the data |
| **Identifier** | A searchable value of a consignment, such as a vehicle plate, vessel IMO number or container id |
| **Dataset** | The full eFTI data of a consignment, held by the platform and fetched on demand |
| **UIL** | Unique identifier link: `gateId`, `platformId`, `datasetId`. The address of a dataset. [UIL](../data/uil.md) |
| **Subset** | A named part of the eFTI data an authority may see, for example `EU01`. [eFTI subsets](../data/subsets.md) |
| **Cabotage** | Domestic road transport by a foreign carrier. The `expire-identifiers` job marks a road consignment `INACTIVE` once its carrier acceptance date is more than 14 days ago (Reg 2024/1942 Art 11(4)) |
| **Follow-up** | A message from an authority to a platform about a dataset the authority fetched |
| **Search criteria** | The fields an authority can search by. [Search criteria](../data/search-criteria.md) |
| **Append-only** | Rows are never updated or deleted in place. A change is a new row; the latest wins. Old rows are archived |

## Messages

| Code | Direction | Purpose |
|---|---|---|
| **FTI004** | platform → gate | Upload identifiers (answered by **FTI029**) |
| **FTI019** | gate → gate | Identifier query (answered by **FTI021**) |
| **FTI009** | gate → gate, gate → platform | Dataset request (answered by **FTI010**) |
| **FTI025** | gate → gate, gate → platform | Follow-up request (answered by **FTI030**) |

Pages: [Peer gates (AS4)](../api/gates/index.md), [Upload (eDelivery)](../api/platforms/upload-edelivery.md),
[Dataset content (FTI010)](../data/dataset-fields.md).

## Transport and identity

| Term | Meaning |
|---|---|
| **eDelivery / AS4** | The EU messaging profile gates use between each other. Messages are signed and encrypted |
| **MSH** | Message service handler: the AS4 endpoint, `POST /services/msh` |
| **Party map** | edelivery's list of gates and platforms it may exchange AS4 messages with, built from the registry |
| **X-Road** | The national data exchange layer authorities use to call the gate |
| **Security Server** | The X-Road gateway of an organisation. The gate trusts the `X-Road-Client` it forwards |
| **`X-Road-Id`** | A UUID per request. Also the key of a cross-gate search |
| **Fan-out** | The gate asking all online peer gates when the local register has no match |
| **Polling** | Asking again with `{"poll": true, "searchId": …}` until `x-poll-more` is `false` |
| **TARA** | The Estonian state authentication service used to log in to the admin UI |
| **TIM** | Token and Identity Manager: runs the TARA exchange and issues the gate's own JWT |
| **Turvis** | The separate service that does AS4 signing, verification, encryption and decryption in production. Opt-in. [Turvis](../api/internal/turvis.md) |
| **Internal service token** | The shared secret that gate components use on `/efti/api/v1/**`. Not for partners |

## Registry

| Term | Meaning |
|---|---|
| **Registry** | The YAML files in `registry/` that declare gates, platforms and authorities. [Change the registry](../roles/registry-changes.md) |
| **`registry-build`** | The one-shot step that validates the files and publishes them as JSON. It fails the start on any error |
| **`status`** | `ONLINE` or `DISABLED` for gates and platforms. Authorities have none |
| **Trust view** | The filtered copy of the gates for Turvis. It holds no platform data |
| **Pinning** | Accepting a gate only if its certificate equals the registered one |

## Components

| Term | Meaning |
|---|---|
| **Ruuter** | The DSL engine that serves the HTTP routes from YAML. The main one and the separate `ruuter-xroad` |
| **ReSql** | Serves SQL files as HTTP endpoints |
| **edelivery** | The Kotlin service that speaks AS4 |
| **xml-mapper** | The Kotlin service that converts eFTI XML to JSON and back |
| **CronManager** | The scheduler that calls the `/ops/v1/*` housekeeping jobs |
| **Developer mock** | A public sandbox that imitates the X-Road provider API with canned answers |
| **Mock platform** | A built-in platform that answers dataset and follow-up calls with canned data |
| **Liquibase** | Runs the database migrations |
| **ADR** | Architecture decision record. [Decision records](../architecture/decisions/index.md) |
