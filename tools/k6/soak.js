// plans/phase-08-load-and-observability.md's soak.js: "100 rps for 2 hours. Leaks, unbounded
// growth, slow drift." DURATION defaults to the full 2 hours the phase specifies; task 08.10's
// actual run overrides it to a shorter window and documents the scale-down explicitly in
// docs/performance.md, per the phase's own "numbers on a laptop are not production numbers" note
// - the script itself stays correct against the spec either way.
//
//   docker run --rm -i --network revolut_default -v "%cd%/tools/k6:/scripts" grafana/k6 run \
//       -e DURATION=15m /scripts/soak.js
import http from 'k6/http';
import { check } from 'k6';
import { setupUsers } from './lib/setup.js';
import { getToken, authHeaders } from './lib/auth.js';
import { BASE_URL } from './lib/config.js';

const USER_COUNT = parseInt(__ENV.USER_COUNT || '50', 10);
const RATE = parseInt(__ENV.RATE || '100', 10);
const DURATION = __ENV.DURATION || '2h';

export const options = {
    // See hotspot.js's identical comment - k6's default setup() timeout (60s) is too short for
    // setupUsers() registering/funding many users one at a time.
    setupTimeout: '5m',
    scenarios: {
        soak: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 200,
            maxVUs: 400,
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        // No latency threshold here on purpose: the thing this scenario looks for is *drift* -
        // p95 at hour 2 compared with p95 at minute 5 - which only shows up by reading the
        // dashboard's own time series after the run, not from a single pass/fail number.
    },
};

export function setup() {
    return setupUsers(USER_COUNT);
}

export default function (users) {
    const sender = users[__VU % users.length];
    const recipient = users[(__VU + 1) % users.length];
    const token = getToken(sender.email, sender.password, sender.tokenSeed);
    const idemKey = `k6-soak-${__VU}-${__ITER}-${Date.now()}`;

    const res = http.post(`${BASE_URL}/api/payments`, JSON.stringify({
        sourceAccountId: sender.accounts.EUR.accountId,
        targetAccountId: recipient.accounts.EUR.accountId,
        amountMinor: 100,
        currency: 'EUR',
        reference: 'k6 soak.js',
    }), {
        headers: authHeaders(token, { 'Content-Type': 'application/json', 'Idempotency-Key': idemKey }),
        tags: { name: 'Transfer' },
    });

    check(res, { 'transfer accepted 202': (r) => r.status === 202 });
}
