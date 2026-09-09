# Performance report of the EE eFTI Gate using Ruuter

Performance is tested using the ApacheBench (ab) - Apache HTTP server benchmarking tool.

### Identifiers Request (Local Gate)
| Requests (-n) | Concurrent (-c) | Request/Sec (mean) | 99% Requests (ms) | vs POC        |
|:-------------:|:---------------:|:------------------:|:-----------------:|:-------------:|
|      100      |       10        |          70        |       240         | x 240 slower  |
|     1 000     |       100       |          93        |      1800         | x 160 slower  |
|     5 000     |       250       |          93        |      4300         | x 153 slower  |

`ab -n 100 -c 10 -T application/json -H 'X-Request-ID: 073b32bc-a081-11f1-8d67-3c9c0f2eb459' -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' -p search.json http://localhost:8086/efti/api/v1/authority/search`

### Identifiers Request (Local Gate, 1M consignments in the DB)

TODO: POST-ed 100000 consignments in X ms (X ms per consignment) using 100 threads.
Per consignment insert: ~2sec

| Requests (-n) | Concurrent (-c) | Request/Sec (mean) | 99% Requests (ms) |
|:-------------:|:---------------:|:------------------:|:-----------------:|
|      15       |       10        |      0.6           |       15 000      |


Hardware used for testing was Intel(R) Core(TM) Ultra 9 386H, 16 cores
