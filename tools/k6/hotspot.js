// plans/phase-08-load-and-observability.md's hotspot.js: "500 rps all targeting one account.
// Worst case for optimistic locking." Many senders, one fixed recipient - every single transfer
// in this run contends on that one account_balance row's optimistic-lock version. This is the
// concrete lesson behind the Phase-00 partitioning decision: keying by account id gives
// per-account ordering, and the price is that a hot account is a serialisation point. Watch
// ledger.optimistic.retries (the dashboard's Resources row) climb while this runs.
//
//   docker run --rm -i --network revolut_default -v "%cd%/tools/k6:/scripts" grafana/k6 run \
//       /scripts/hotspot.js
import http from 'k6/http';
import { check } from 'k6';
import { setupUsers } from './lib/setup.js';
import { getToken, authHeaders } from './lib/auth.js';
import { BASE_URL } from './lib/config.js';

// Senders, not the hot account itself - one extra user, set aside as the single contended
// recipient every sender transfers into.
const SENDER_COUNT = parseInt(__ENV.SENDER_COUNT || '50', 10);

export const options = {
    // k6's default setup() timeout is 60s. setupUsers() registers/funds users one at a time
    // (see setup.js), so any run with more than a handful of them - every script in this
    // directory, by default - needs real headroom here, not just this one scenario's user count.
    setupTimeout: '5m',
    scenarios: {
        hotspot: {
            executor: 'constant-arrival-rate',
            rate: 500,
            timeUnit: '1s',
            duration: __ENV.DURATION || '2m',
            preAllocatedVUs: 300,
            maxVUs: 800,
        },
    },
    // No http_req_failed threshold: a real run against one hot account at 500 rps genuinely
    // fails a large fraction of transfers once PostingEngine's three retry attempts (task 02.5)
    // aren't enough to win the lock - a smoke test at these settings measured 45% - and that
    // failure rate *is* the measurement this script exists to produce, not a bug to gate the
    // run on. Read it from the summary and the dashboard's ledger.optimistic.retries panel,
    // not from k6's own exit code.
};

export function setup() {
    const users = setupUsers(SENDER_COUNT + 1);
    return { senders: users.slice(0, SENDER_COUNT), hotAccount: users[SENDER_COUNT] };
}

export default function (data) {
    const sender = data.senders[__VU % data.senders.length];
    const token = getToken(sender.email, sender.password, sender.tokenSeed);
    const idemKey = `k6-hotspot-${__VU}-${__ITER}-${Date.now()}`;

    const res = http.post(`${BASE_URL}/api/payments`, JSON.stringify({
        sourceAccountId: sender.accounts.EUR.accountId,
        targetAccountId: data.hotAccount.accounts.EUR.accountId,
        amountMinor: 10,
        currency: 'EUR',
        reference: 'k6 hotspot.js',
    }), {
        headers: authHeaders(token, { 'Content-Type': 'application/json', 'Idempotency-Key': idemKey }),
        tags: { name: 'Transfer' },
    });

    check(res, { 'transfer accepted 202': (r) => r.status === 202 });
}
