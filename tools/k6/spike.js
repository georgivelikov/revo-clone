// plans/phase-08-load-and-observability.md's spike.js: "50 rps -> 1000 rps instantly, 60 s, back
// down. Lag spike and drain time." The 1-second transition stage is the "instantly" - k6's
// ramping-arrival-rate still interpolates within a stage, so a near-zero duration is what makes
// the jump read as a step rather than a ramp.
//
//   docker run --rm -i --network revolut_default -v "%cd%/tools/k6:/scripts" grafana/k6 run \
//       /scripts/spike.js
import http from 'k6/http';
import { check } from 'k6';
import { setupUsers } from './lib/setup.js';
import { getToken, authHeaders } from './lib/auth.js';
import { BASE_URL } from './lib/config.js';

const USER_COUNT = parseInt(__ENV.USER_COUNT || '100', 10);

export const options = {
    // See hotspot.js's identical comment - k6's default setup() timeout (60s) is too short for
    // setupUsers() registering/funding many users one at a time.
    setupTimeout: '5m',
    scenarios: {
        spike: {
            executor: 'ramping-arrival-rate',
            startRate: 50,
            timeUnit: '1s',
            preAllocatedVUs: 300,
            maxVUs: 1200,
            stages: [
                { target: 50, duration: '15s' },   // baseline, so the jump has something to jump from
                { target: 1000, duration: '1s' },  // instantly
                { target: 1000, duration: '60s' },
                { target: 50, duration: '1s' },    // back down, just as abruptly
                { target: 50, duration: '30s' },   // drain-and-recover window
            ],
        },
    },
    // No http_req_failed threshold: a real instant 20x step genuinely produces failures while
    // the system reacts - a smoke test at these settings measured 37% during the 1000 rps
    // plateau, with token/funding checks unaffected (the auth path was not the bottleneck here).
    // That failure rate, and how fast it recovers once the rate drops back to 50, *is* the
    // measurement - "lag spike and drain time" - not a bug to gate the run on.
};

export function setup() {
    return setupUsers(USER_COUNT);
}

export default function (users) {
    const sender = users[__VU % users.length];
    const recipient = users[(__VU + 1) % users.length];
    const token = getToken(sender.email, sender.password, sender.tokenSeed);
    const idemKey = `k6-spike-${__VU}-${__ITER}-${Date.now()}`;

    const res = http.post(`${BASE_URL}/api/payments`, JSON.stringify({
        sourceAccountId: sender.accounts.EUR.accountId,
        targetAccountId: recipient.accounts.EUR.accountId,
        amountMinor: 100,
        currency: 'EUR',
        reference: 'k6 spike.js',
    }), {
        headers: authHeaders(token, { 'Content-Type': 'application/json', 'Idempotency-Key': idemKey }),
        tags: { name: 'Transfer' },
    });

    check(res, { 'transfer accepted 202': (r) => r.status === 202 });
}
