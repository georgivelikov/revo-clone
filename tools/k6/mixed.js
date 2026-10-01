// plans/phase-08-load-and-observability.md's mixed.js: "60% transfers, 20% FX, 15% card auth,
// 5% reads. Realistic contention across services." Rate is flat, not ramped - the question this
// scenario answers is about *contention between services*, not where throughput breaks.
//
//   docker run --rm -i --network revolut_default -v "%cd%/tools/k6:/scripts" grafana/k6 run \
//       -e DURATION=5m -e RATE=100 /scripts/mixed.js
//
// The FX slice exercises the cross-currency path docs/failure-modes.md's Experiment 7 section
// documents as broken independently of any chaos lever (a relayed bearer token lost across
// Resilience4j's circuit-breaker thread) - expect its checks to fail until that is fixed. That is
// real data for this scenario, not a reason to skip the slice: "realistic contention across
// services" includes a service that currently cannot complete its share of the mix.
import http from 'k6/http';
import { check } from 'k6';
import { setupUsers } from './lib/setup.js';
import { getToken, authHeaders } from './lib/auth.js';
import { BASE_URL, SIMULATOR_URL } from './lib/config.js';

const USER_COUNT = parseInt(__ENV.USER_COUNT || '50', 10);
const RATE = parseInt(__ENV.RATE || '100', 10);
const DURATION = __ENV.DURATION || '5m';

export const options = {
    // See hotspot.js's identical comment - k6's default setup() timeout (60s) is too short for
    // setupUsers() registering/funding many users one at a time.
    setupTimeout: '5m',
    scenarios: {
        mixed: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 200,
            maxVUs: 600,
        },
    },
    thresholds: {
        // No http_req_failed threshold at the top level - the FX slice is expected to fail until
        // the bug docs/failure-modes.md §3 describes is fixed, which would otherwise abort the
        // whole run on its very first iteration. Per-slice thresholds instead.
        'http_req_duration{name:Transfer}': ['p(95)<1000'],
        'http_req_duration{name:CardAuth}': ['p(95)<1000'],
        'http_req_duration{name:Read}': ['p(95)<500'],
    },
};

export function setup() {
    return setupUsers(USER_COUNT, { issueCards: true, fundCurrencies: ['EUR', 'GBP'] });
}

export default function (users) {
    const user = users[__VU % users.length];
    const other = users[(__VU + 1) % users.length];
    const token = getToken(user.email, user.password, user.tokenSeed);
    const roll = Math.random();

    if (roll < 0.60) {
        transfer(user, other, token);
    } else if (roll < 0.80) {
        crossCurrencyTransfer(user, other, token);
    } else if (roll < 0.95) {
        cardAuth(user);
    } else {
        readAccounts(token);
    }
}

function transfer(sender, recipient, token) {
    const idemKey = `k6-mixed-transfer-${__VU}-${__ITER}-${Date.now()}`;
    const res = http.post(`${BASE_URL}/api/payments`, JSON.stringify({
        sourceAccountId: sender.accounts.EUR.accountId,
        targetAccountId: recipient.accounts.EUR.accountId,
        amountMinor: 100,
        currency: 'EUR',
        reference: 'k6 mixed.js transfer',
    }), {
        headers: authHeaders(token, { 'Content-Type': 'application/json', 'Idempotency-Key': idemKey }),
        tags: { name: 'Transfer' },
    });
    check(res, { 'transfer accepted 202': (r) => r.status === 202 });
}

function crossCurrencyTransfer(sender, recipient, token) {
    const idemKey = `k6-mixed-fx-${__VU}-${__ITER}-${Date.now()}`;
    const res = http.post(`${BASE_URL}/api/payments`, JSON.stringify({
        sourceAccountId: sender.accounts.EUR.accountId,
        targetAccountId: recipient.accounts.GBP.accountId,
        amountMinor: 100,
        currency: 'EUR',
        reference: 'k6 mixed.js fx',
    }), {
        headers: authHeaders(token, { 'Content-Type': 'application/json', 'Idempotency-Key': idemKey }),
        tags: { name: 'CrossCurrencyTransfer' },
    });
    check(res, { 'fx transfer accepted 202': (r) => r.status === 202 });
}

function cardAuth(user) {
    if (!user.cardId) {
        return;
    }
    const res = http.post(`${SIMULATOR_URL}/simulator/cards/spend`, JSON.stringify({
        cardId: user.cardId,
        merchant: 'k6 mixed.js merchant',
        amountMinor: 500,
        currency: 'EUR',
    }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'CardAuth' } });
    check(res, { 'card spend accepted 202': (r) => r.status === 202 });
}

function readAccounts(token) {
    const res = http.get(`${BASE_URL}/api/accounts/me`, {
        headers: authHeaders(token),
        tags: { name: 'Read' },
    });
    check(res, { 'read accounts 200': (r) => r.status === 200 });
}
