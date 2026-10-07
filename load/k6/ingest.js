// Load test for POST /v1/events: a ramping arrival rate (requests per second, independent of how
// fast the server answers), events spread over 200 accounts.
//
//   podman run --rm -i --network ratekit_default -e TARGET=http://ingest:8081 grafana/k6:2.3.0 run - < load/k6/ingest.js
//
// Environment: TARGET (default http://ingest:8081), RUN (makes event ids unique), STAGE_SECONDS
// (default 10), RATES (comma separated target rates, default 200,400,800,1200,1600).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const TARGET = __ENV.TARGET || 'http://ingest:8081';
const RUN = __ENV.RUN || String(Date.now());
const STAGE_SECONDS = Number(__ENV.STAGE_SECONDS || 10);
const RATES = (__ENV.RATES || '200,400,800,1200,1600').split(',').map(Number);
const ACCOUNTS = 200;

const accepted = new Counter('events_accepted');

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: RATES[0],
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      stages: RATES.map((target) => ({ target, duration: `${STAGE_SECONDS}s` })),
    },
  },
};

const headers = { 'Content-Type': 'application/json' };

export default function () {
  const n = `${RUN}-${__VU}-${__ITER}`;
  const body = JSON.stringify({
    eventId: `ev-${n}`,
    accountId: `load-acc-${Math.floor(Math.random() * ACCOUNTS)}`,
    meter: 'sms-load',
    quantity: 1,
    occurredAt: new Date().toISOString(),
  });
  const res = http.post(`${TARGET}/v1/events`, body, { headers });
  const ok = check(res, { 'status is 202': (r) => r.status === 202 });
  if (ok) accepted.add(1);
}
