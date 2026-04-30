# Performance report of the DLK PoC eFTI Gate

Performance is tested using the ApacheBench (ab) - Apache HTTP server benchmarking tool.

### Identifiers Request (Local Gate)
| Requests (-n) | Concurrent (-c) | Request/Sec (mean) | 99% Requests (ms) |
|:-------------:|:---------------:|:------------------:|:-----------------:|
|      100      |       10        |       1 775        |         7         |
|     1 000     |       100       |       8 529        |        15         |
|     5 000     |       250       |       9 542        |        40         |

### Identifiers Request (Remote gate through EDelivery)
| Requests (-n) | Concurrent (-c) | Request/Sec (mean) | 99% Requests (ms) |
|:-------------:|:---------------:|:------------------:|:-----------------:|
|      100      |       10        |        179         |        66         |
|     1 000     |       100       |        387         |        438        |
|     5 000     |       250       |        392         |       1584        |

### Dataset Request (Local platform)
| Requests (-n) | Concurrent (-c) | Request/Sec (mean) | 99% Requests (ms) |
|:-------------:|:---------------:|:------------------:|:-----------------:|
|      100      |       10        |        259         |        45         |
|     1 000     |       100       |       2 333        |        53         |
|     5 000     |       250       |       5 674        |        64         |

### Dataset Request (Remote platform through EDelivery)
| Requests (-n) | Concurrent (-c) | Request/Sec (mean) | 99% Requests (ms) |
|:-------------:|:---------------:|:------------------:|:-----------------:|
|      100      |       10        |        172         |        76         |
|     1 000     |       100       |        353         |        517        |
|     5 000     |       250       |        380         |       1729        |

Hardware used for testing was 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz, 8 cores
