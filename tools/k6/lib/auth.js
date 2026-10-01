import http from 'k6/http';
import { check } from 'k6';
import { KEYCLOAK_TOKEN_URL } from './config.js';

// Module-scope, not VU-scope by accident: k6 gives every VU its own JS runtime instance, so this
// object is naturally per-VU - exactly what a token cache needs to be, since one VU's token must
// never leak into another VU's request. A fresh password-grant call on every single iteration
// would both waste Keycloak round-trips the test isn't trying to measure and add authentication
// latency on top of whatever the script's own check is timing.
const tokenCache = {};

/** One password-grant call. Exposed separately so `setup()` can pre-fetch a seed token - see `getToken`. */
export function fetchFreshToken(email, password) {
    const body = `grant_type=password&client_id=revolut-web&username=${encodeURIComponent(email)}` +
        `&password=${encodeURIComponent(password)}&scope=openid`;
    const res = http.post(KEYCLOAK_TOKEN_URL, body, {
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        tags: { name: 'KeycloakToken' },
    });
    check(res, { 'token obtained': (r) => r.status === 200 });
    const parsed = JSON.parse(res.body);
    return { token: parsed.access_token, expiresAt: Date.now() + parsed.expires_in * 1000 };
}

/**
 * Returns a cached, still-valid access token for this user, or obtains a fresh one. Refreshes
 * 10 seconds before actual expiry so a token never goes stale mid-request on a slow iteration.
 *
 * `seed` - a `{token, expiresAt}` pre-fetched once in `setup()` and threaded through the
 * scenario's own user object - matters at any real concurrency. `setup()` runs once, in one
 * context; every VU gets its own fresh (empty) `tokenCache`, so with no seed every VU's first
 * iteration for a given user is a cold password-grant call. A scenario with many VUs cycling a
 * small user pool (hotspot.js: up to 800 VUs, as few as 10 users) turns that into hundreds of
 * simultaneous logins against one dev-mode Keycloak and the token endpoint becomes the
 * bottleneck the scenario was never trying to measure - caught by running hotspot.js in this
 * task, not inferred from reading the code. Passing the seed through makes a VU's *first* call
 * free too, not just its later ones.
 */
export function getToken(email, password, seed) {
    if (seed && seed.expiresAt > Date.now() + 10000) {
        tokenCache[email] = seed;
        return seed.token;
    }
    const cached = tokenCache[email];
    if (cached && cached.expiresAt > Date.now() + 10000) {
        return cached.token;
    }
    const fresh = fetchFreshToken(email, password);
    tokenCache[email] = fresh;
    return fresh.token;
}

export function authHeaders(token, extra) {
    return Object.assign({ Authorization: `Bearer ${token}` }, extra || {});
}
