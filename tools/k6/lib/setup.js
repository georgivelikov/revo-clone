import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { BASE_URL, LEDGER_URL, FUND_AMOUNT_MINOR, TEST_USER_PASSWORD, RUN_ID } from './config.js';
import { authHeaders, fetchFreshToken } from './auth.js';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

/**
 * Registers `count` fresh users, waits for each one's EUR (and, if `fundCurrencies` names more,
 * every other requested currency) wallet to get a `ledgerAccountId` from the registration saga,
 * then funds each via ledger-service's funding endpoint directly - the same
 * `EXTERNAL_CLEARING -> wallet` path task 02.11 built, not a transfer from another test user,
 * so setup never competes with the scenario it is building fixtures for.
 *
 * Call this once from a script's own `export function setup()` - never from `default()`, which
 * runs per-iteration-per-VU and would register a fresh user on every single request.
 *
 * Each returned user carries `tokenSeed` - a token fetched once here, in `setup()`'s single
 * shared context - threaded through to `getToken(email, password, user.tokenSeed)` in a
 * scenario's `default()` so a VU's very first call for that user is not a cold password-grant
 * call too. See auth.js's `getToken` for why that matters at real concurrency.
 *
 * @param {number} count how many users to create
 * @param {{issueCards: boolean, fundCurrencies: string[]}} [options]
 * @returns {Array<{email: string, password: string, tokenSeed: {token: string, expiresAt: number}, accounts: Record<string, {accountId: string, ledgerAccountId: string}>, cardId: string|null}>}
 */
export function setupUsers(count, options) {
    const opts = Object.assign({ issueCards: false, fundCurrencies: ['EUR'] }, options || {});
    const users = [];
    for (let i = 0; i < count; i++) {
        users.push(registerAndFund(i, opts));
    }
    return users;
}

function registerAndFund(index, opts) {
    const email = `k6-${RUN_ID}-${index}@revolutclone.local`;

    const registerRes = http.post(`${BASE_URL}/api/identity/register`, JSON.stringify({
        email,
        password: TEST_USER_PASSWORD,
        firstName: 'LoadTest',
        lastName: `User${index}`,
        country: 'GB',
    }), { headers: JSON_HEADERS, tags: { name: 'Register' } });
    // 409 means a previous, not-yet-cleaned-up run already created this exact (runId, index)
    // pair - safe to treat as already-done rather than a failure, since RUN_ID defaults to the
    // current timestamp and only collides on an explicit, deliberate re-run with the same id.
    if (registerRes.status !== 201 && registerRes.status !== 409) {
        fail(`register failed for ${email}: ${registerRes.status} ${registerRes.body}`);
    }

    const tokenSeed = fetchFreshToken(email, TEST_USER_PASSWORD);
    const token = tokenSeed.token;
    const accounts = {};
    for (const currency of opts.fundCurrencies) {
        accounts[currency] = awaitAndFundWallet(email, token, currency);
    }

    let cardId = null;
    if (opts.issueCards) {
        cardId = issueCard(token, accounts[opts.fundCurrencies[0]].accountId);
    }

    return { email, password: TEST_USER_PASSWORD, tokenSeed, accounts, cardId };
}

/**
 * Polls `/api/accounts/me` until `currency`'s wallet has a `ledgerAccountId`, then funds it.
 * 60 attempts at 0.5s - 30s total - is generous on purpose: the registration saga is a four-hop
 * async chain (identity -> account opens the wallet -> ledger creates the ledger_account and
 * publishes back -> account fills in ledgerAccountId), and setupUsers() runs these one user at a
 * time, so a run with many test users is itself adding queueing delay to this wait by the time
 * it reaches the last one.
 */
function awaitAndFundWallet(email, token, currency) {
    let wallet = null;
    for (let attempt = 0; attempt < 60 && !wallet; attempt++) {
        const res = http.get(`${BASE_URL}/api/accounts/me`, {
            headers: authHeaders(token),
            tags: { name: 'AccountsMe' },
        });
        if (res.status === 200) {
            const found = JSON.parse(res.body).find(
                (a) => a.currency === currency && a.type === 'WALLET' && a.ledgerAccountId);
            if (found) {
                wallet = found;
            }
        }
        if (!wallet) {
            sleep(0.5);
        }
    }
    if (!wallet) {
        fail(`${email}'s ${currency} wallet never got a ledgerAccountId`);
    }

    const fundRes = http.post(`${LEDGER_URL}/internal/ledger/admin/fund/${wallet.ledgerAccountId}`,
        JSON.stringify({ amountMinor: FUND_AMOUNT_MINOR, currency }),
        { headers: authHeaders(token, JSON_HEADERS), tags: { name: 'Fund' } });
    check(fundRes, { 'funded 201': (r) => r.status === 201 });

    return { accountId: wallet.id, ledgerAccountId: wallet.ledgerAccountId };
}

function issueCard(token, accountId) {
    const res = http.post(`${BASE_URL}/api/cards`, JSON.stringify({
        accountId,
        type: 'VIRTUAL',
        label: 'k6 load test card',
        pin: '1234',
    }), { headers: authHeaders(token, JSON_HEADERS), tags: { name: 'IssueCard' } });
    check(res, { 'card issued 201': (r) => r.status === 201 });
    return JSON.parse(res.body).id;
}
