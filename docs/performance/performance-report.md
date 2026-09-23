# Performance report of the EE eFTI Gate using Ruuter

Performance is tested using the ApacheBench (ab) - Apache HTTP server benchmarking tool.

Hardware used for testing was Intel(R) Core(TM) Ultra 9 386H, 16 cores

### Identifiers Request (Local Gate)
| Requests (-n) | Concurrent (-c) | Request/Sec (mean) | 99% Requests (ms) | vs POC                       |
|:-------------:|:---------------:|:------------------:|:-----------------:|:----------------------------:|
|      100      |       10        |         660        |        22         |  ~10x slower (2ms/7000rps)   |
|     1 000     |       100       |         630        |       243         |  ~20x slower (12ms/15000rps) |
|     5 000     |       250       |         620        |       745         |  ~30x slower (26ms/18000rps) |

Concurrency is also ~30x lower than the POC. Performance degrades much more quickly with increased concurrency.

```sh
ab -n 100 -c 10 -T application/json -H 'X-Request-ID: 073b32bc-a081-11f1-8d67-3c9c0f2eb459' -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' -p search.json http://localhost:8086/efti/api/v1/authority/search`
```

### Upload of identifiers

Uploaded 1000000 consignments in 4719 sec using 100 threads (4.7 ms per consignment, 47x slower than POC total of 99 sec).

Using POC DemoPlatform's BulkConsignmentGenerator.

### Identifiers Request (Local Gate, 1M consignments in the DB)

| Requests (-n) | Concurrent (-c) | Request/Sec (mean) | 99% Requests (ms) |
|:-------------:|:---------------:|:------------------:|:-----------------:|
|     100       |       10        |      588           |           23      |
|    1000       |      100        |      660           |          253      |
|    5000       |      250        |      648           |          754      |


The results are similar to the previous test, so the indexing works in the DB.
Real-world performance will be slightly worse as the DB will contain 3-5x more stale records due to the append-only nature.

### Identifiers Request (Remote Gate via multiplexer+eDelivery)

| Requests (-n) | Concurrent (-c) | Request/Sec (mean) | 99% Requests (ms) |
|:-------------:|:---------------:|:------------------:|:-----------------:|
|     100       |       10        |      217           |           69      |
|    1000       |      100        |      197           |          829      |
|    5000       |      250        |      105           |        36800      |

