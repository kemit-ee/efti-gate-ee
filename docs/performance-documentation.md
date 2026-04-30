# Performance Documentation of PoC

## Table of contents
- [Introduction](#introduction)
- [Hardware used](#hardware-used)
- [Performance](#performance)
- [Test Fest 3 results](#test-fest-3-results)
- [Conclusion](#conclusion)

## Introduction
This document describes the performance characteristics of this implementation of eFTI gate software.

How was this performance reached? check out the [architecture document](architecture.md)

## Hardware used
The hardware used by DLK for all the PoC uses. This includes Test Fest 3 and the performance tests that can be seen in the next chapter.

We used a single [Hetzner](https://www.hetzner.com/) Virtual Private Server (VPS) with properties:
- 8 VCPU
- 16GB RAM
- 19.49€/month

The eFTI gate PoC was running in a single docker container inside that VPS. So a single instance inside a single node, no horizontal scaling and load balancing.

## Performance
Here is a table that shows performance of **2 connected eFTI gate PoC's** making all eFTI requests at the same time.
The gates were on different Hetzner servers - so the communication is not local.

Performance was tested using 2 different communication adapters - eDelivery and fast REST API, to analyze the performance impacts of eDelivery usage.

The datasets queried and the identifiers uploaded are the size that they would be in real world - dataset: 300kB and identifier 1kB.

Duration of the test is 15 minutes.

The table shows the results of a single gate:

### Identifier Query (Broadcast)

| Metric              | eDelivery | Fast Adapter | Improvement |
|---------------------|-----------|--------------|-------------|
| Average time        | 73.89 ms  | 14.88 ms     | 5x          |
| Median time         | 24.90 ms  | 11.80 ms     | 2x          |
| 95% response time   | 86.47 ms  | 19.04 ms     | 4x          |
| Requests per second | 100       | 100          | -           |
| Total requests made | 89354     | 89938        | -           |
| Success rate        | 100%      | 100%         | -           |

---

### Dataset Query (Remote)

| Metric              | eDelivery | Fast Adapter | Improvement |
|---------------------|-----------|--------------|-------------|
| Average time        | 89.92 ms  | 24.49 ms     | 4x          |
| Median time         | 33.04 ms  | 21.37 ms     | 1.5x        |
| 95% response time   | 101.87 ms | 29.99 ms     | 3x          |
| Requests per second | 100       | 100          | -           |
| Total requests made | 88970     | 89930        | -           |
| Success rate        | 100%      | 100%         | -           |

---

### Dataset Query (Local)

| Metric              | eDelivery | Fast Adapter | Improvement |
|---------------------|-----------|--------------|-------------|
| Average time        | 20.30 ms  | 20.32 ms     | -           |
| Median time         | 18.37 ms  | 18.65 ms     | -           |
| 95% response time   | 27.54 ms  | 27.15 ms     | -           |
| Requests per second | 100       | 100          | -           |
| Total requests made | 89989     | 89990        | -           |
| Success rate        | 100%      | 100%         | -           |

---

### Save Identifiers

| Metric              | eDelivery | Fast Adapter | Improvement |
|---------------------|-----------|--------------|-------------|
| Average time        | 12.71 ms  | 14.88 ms     | -           |
| Median time         | 11.12 ms  | 11.80 ms     | -           |
| 95% response time   | 17.86 ms  | 19.04 ms     | -           |
| Requests per second | 100       | 100          | -           |
| Total requests made | 89991     | 89938        | -           |
| Success rate        | 100%      | 100%         | -           |

### Requests total
| Total Requests Made | eDelivery  | Fast Adapter |
|---------------------|------------|--------------|
| PoC 1               | 358304     | 359852       |
| PoC 2               | 357266     | 359845       |
| Whole System        | **715570** | **719697**   |


## Test Fest 3 results
Following are the results of the PoC (eu-ee31) during Test Fest 3.

Each round was 15 minutes.

### Round A - 1
High response times and failure count is due to **eu-ee12** (estonian gate using reference implementation) failing.

|                          | Identifier Query (Broadcast) | Dataset Query (Remote) | Dataset Query (Local) | Save Identifiers |
|:------------------------:|:----------------------------:|:----------------------:|:---------------------:|:----------------:|
|       Average time       |         52760.58 ms          |      14298.76 ms       |       82.59 ms        |        0         |
|       Median time        |         60142.30 ms          |       2823.86 ms       |       52.02 ms        |        0         |
|    95% response time     |         60224.21 ms          |      60190.37 ms       |       212.64 ms       |        0         |
| Requests made per second |             0.12             |          0.27          |         0.08          |       0.00       |
|   Total requests Made    |             109              |          244           |          72           |        0         |
|       Success rate       |            95.41%            |         86.07%         |        100.00%        |        0         |

#### Identifier Query Failures
|  Gate  | Amount of Requests Failed |
|:------:|:-------------------------:|
| EU-AT1 |             2             |
| EU-FR1 |             1             |
| fi-tst |             2             |

#### Remote Dataset Query Failures
|  Gate   | Amount of Requests Failed |
|:-------:|:-------------------------:|
| eu-ee12 |            34             |

### Round A - 2
High response times and failure count is due to **EU-IT1** failing.

|                          | Identifier Query (Broadcast) | Dataset Query (Remote) | Dataset Query (Local) | Save Identifiers |
|:------------------------:|:----------------------------:|:----------------------:|:---------------------:|:----------------:|
|       Average time       |         55852.83 ms          |      16587.87 ms       |      1312.29 ms       |        0         |
|       Median time        |         60183.07 ms          |       2591.14 ms       |       53.02 ms        |        0         |
|    95% response time     |         60761.09 ms          |      60364.39 ms       |       262.41 ms       |        0         |
| Requests made per second |             0.12             |          0.27          |         0.08          |       0.00       |
|   Total requests Made    |             109              |          244           |          73           |        0         |
|       Success rate       |            78.90%            |         79.10%         |        100.00%        |        0         |

#### Identifier Query Failures
|  Gate  | Amount of Requests Failed |
|:------:|:-------------------------:|
| EU-AT1 |             2             |
| EU-FR1 |             3             |
| fi-tst |             2             |
| EU-IT1 |            16             |

#### Remote Dataset Query Failures
|  Gate  | Amount of Requests Failed |
|:------:|:-------------------------:|
| EU-IT1 |            49             |
| eude1  |             1             |
| fi-tst |             1             |

### Round A - 3
This was the only round where none of the gates slowed the whole system down.

|                          | Identifier Query (Broadcast) | Dataset Query (Remote) | Dataset Query (Local) | Save Identifiers |
|:------------------------:|:----------------------------:|:----------------------:|:---------------------:|:----------------:|
|       Average time       |          3577.77 ms          |       2591.68 ms       |       64.38 ms        |        0         |
|       Median time        |          2134.26 ms          |       2695.72 ms       |       51.18 ms        |        0         |
|    95% response time     |          5926.81 ms          |       5564.52 ms       |       134.16 ms       |        0         |
| Requests made per second |             0.12             |          0.27          |         0.08          |       0.00       |
|   Total requests Made    |             108              |          244           |          73           |        0         |
|       Success rate       |            98.15%            |         99.59%         |        100.00%        |        0         |

#### Identifier Query Failures
|  Gate  | Amount of Requests Failed |
|:------:|:-------------------------:|
| EU-AT1 |             1             |
| fi-tst |             1             |

#### Remote Dataset Query Failures
|  Gate  | Amount of Requests Failed |
|:------:|:-------------------------:|
| eude1  |             1             |

### Round B
High response times and failure count is due to **EU-FR1** failing.

Also, PoC's 6/217 save identifiers failed because our previous reverse-proxy ([Traefik](https://github.com/traefik/traefik)) did not handle the load.
We now have changed to [Caddy](https://github.com/caddyserver/caddy) and seems that it does handle such load.

|                          | Identifier Query (Broadcast) | Dataset Query (Remote) | Dataset Query (Local) | Save Identifiers |
|:------------------------:|:----------------------------:|:----------------------:|:---------------------:|:----------------:|
|       Average time       |         51687.79 ms          |      19778.68 ms       |      1322.35 ms       |    2525.26 ms    |
|       Median time        |         60228.36 ms          |       2701.34 ms       |       52.92 ms        |     20.04 ms     |
|    95% response time     |         60685.24 ms          |      60477.02 ms       |       261.81 ms       |    193.04 ms     |
| Requests made per second |             0.12             |          0.27          |         0.08          |       0.24       |
|   Total requests Made    |             109              |          244           |          72           |       217        |
|       Success rate       |            83.49%            |         76.64%         |        100.00%        |      97.24%      |

#### Identifier Query Failures
|  Gate  | Amount of Requests Failed |
|:------:|:-------------------------:|
| EU-FR1 |            18             |

#### Remote Dataset Query Failures
|  Gate  | Amount of Requests Failed |
|:------:|:-------------------------:|
| EU-FR1 |            54             |
| eude1  |             2             |
| fi-tst |             1             |

## Conclusion
Performance test indicate that this implementation can handle a lof of parallel requests with low latency, demonstrating that a simpler, more focused approach can meet the demanding requirements of the eFTI network.

This implementation is order of magnitude faster than the previous reference implementation in terms of throughput.

eDelivery has a performance impact of 2-5x comparing to a simpler REST-based gate-to-gate communication, but it is still possible to achieve good overall performance with eDelivery if implemented efficiently.
