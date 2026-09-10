// Realistic load profile for POST /efti/api/v1/authority/search — ramping virtual users with
// per-iteration think-time, so p50/p90/p95/p99 reflect latency under sustained load rather than
// the queue depth of a fire-everything-at-once burst (the `ab -c 250` failure mode).
//
//   docker run --rm --network efti_gate_ee_default -e SCENARIO=search \
//     -v "$PWD/docs/askend_performance:/d" grafana/k6 run /d/k6-authority-search.js
//
// SCENARIO=search  -> full route through Ruuter (default)
// SCENARIO=resql   -> ReSql get_consignments directly (isolates DB + ReSql)

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const SCENARIO = __ENV.SCENARIO || 'search';
const THINK_MIN = Number(__ENV.THINK_MIN || 0.5);   // seconds
const THINK_MAX = Number(__ENV.THINK_MAX || 1.5);

const latency = new Trend('req_latency', true);

export const options = {
  summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'req_latency': ['p(99)<2000'],
  },
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 20 },   // warm ramp
        { duration: '40s', target: 20 },   // hold
        { duration: '20s', target: 50 },
        { duration: '40s', target: 50 },   // hold
        { duration: '20s', target: 100 },
        { duration: '40s', target: 100 },  // hold — peak
        { duration: '20s', target: 0 },    // ramp down
      ],
      gracefulStop: '10s',
    },
  },
};

const RUUTER = 'http://ruuter:8086';
const RESQL = 'http://resql:8090';

const searchBody = JSON.stringify({ mainTransportId: { operator: 'EQ', id: 'VESSEL-001' } });
const resqlBody = JSON.stringify({ gateId: 'EU-EE', criteria: { mainTransportId: { operator: 'EQ', id: 'VESSEL-001' } } });

export default function () {
  let res;
  if (SCENARIO === 'resql') {
    res = http.post(`${RESQL}/efti/get_consignments`, resqlBody, {
      headers: { 'Content-Type': 'application/json' },
    });
  } else {
    res = http.post(`${RUUTER}/efti/api/v1/authority/search`, searchBody, {
      headers: {
        'Content-Type': 'application/json',
        'X-Request-ID': `k6-${__VU}-${__ITER}`,
        'X-Internal-Service-Token': 'dev-internal-service-token-change-me',
      },
    });
  }
  latency.add(res.timings.duration);
  check(res, {
    'status 200': (r) => r.status === 200,
    'has VESSEL-001': (r) => r.body && r.body.indexOf('VESSEL-001') !== -1,
  });
  sleep(THINK_MIN + Math.random() * (THINK_MAX - THINK_MIN));
}
