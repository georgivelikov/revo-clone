// plans/phase-08-load-and-observability.md's transfers.js: "Ramp 10 -> 500 rps over 5 min, hold
// 5 min. Steady-state throughput and where p99 breaks." Task 08.6 runs this at a flat 100 rps for
// the baseline; task 08.7 runs the full ramp to find the knee.
//
// Run via the project's own Kafka/Postgres network, not the host-mapped ports, so service DNS
// names resolve:
//
//   docker run --rm -i --network revolut_default -v "%cd%/tools/k6:/scripts" grafana/k6 run \
//       /scripts/transfers.js
//
// MODE=baseline (task 08.6) runs a flat constant-arrival-rate hold instead of the ramp, at
// BASELINE_RATE (default 100) for BASELINE_DURATION (default 5m) - "record it before tuning
// anything" needs one number at one steady rate, not a curve. Default MODE is the ramp itself
// (task 08.7). Override USER_COUNT, or any tools/k6/lib/config.js default, with -e NAME=value.
import http from 'k6/http';
import { check } from 'k6';
import { setupUsers } from './lib/setup.js';
import { getToken, authHeaders } from './lib/auth.js';
import { BASE_URL } from './lib/config.js';

const USER_COUNT = parseInt(__ENV.USER_COUNT || '50', 10);
const MODE = __ENV.MODE || 'ramp';

const RAMP_SCENARIO = {
    executor: 'ramping-arrival-rate',
    startRate: 10,
    timeUnit: '1s',
    preAllocatedVUs: 200,
    maxVUs: 600,
    stages: [
        { target: 500, duration: '5m' },
        { target: 500, duration: '5m' },
    ],
};

const BASELINE_SCENARIO = {
    executor: 'constant-arrival-rate',
    rate: parseInt(__ENV.BASELINE_RATE || '100', 10),
    timeUnit: '1s',
    duration: __ENV.BASELINE_DURATION || '5m',
    preAllocatedVUs: 100,
    maxVUs: 300,
};

export const options = {
    // See hotspot.js's identical comment - k6's default setup() timeout (60s) is too short for
    // setupUsers() registering/funding many users one at a time.
    setupTimeout: '5m',
    scenarios: {
        transfers: MODE === 'baseline' ? BASELINE_SCENARIO : RAMP_SCENARIO,
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        'http_req_duration{name:Transfer}': ['p(95)<1000'],
    },
};

export function setup() {
    return setupUsers(USER_COUNT);
}

export default function (users) {
    const sender = users[__VU % users.length];
    const recipient = users[(__VU + 1) % users.length];
    const token = getToken(sender.email, sender.password, sender.tokenSeed);
    const idemKey = `k6-transfer-${__VU}-${__ITER}-${Date.now()}`;

    const res = http.post(`${BASE_URL}/api/payments`, JSON.stringify({
        sourceAccountId: sender.accounts.EUR.accountId,
        targetAccountId: recipient.accounts.EUR.accountId,
        amountMinor: 100,
        currency: 'EUR',
        reference: 'k6 transfers.js',
    }), {
        headers: authHeaders(token, { 'Content-Type': 'application/json', 'Idempotency-Key': idemKey }),
        tags: { name: 'Transfer' },
    });

    check(res, { 'transfer accepted 202': (r) => r.status === 202 });
}
