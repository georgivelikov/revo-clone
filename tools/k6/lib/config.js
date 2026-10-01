// Shared endpoints for every script in tools/k6/. All overridable via -e so these scripts run
// the same way whether k6 is attached to the compose network (the default - see each script's
// own header comment for the exact `docker run` invocation) or run natively against the
// host-mapped ports.
export const BASE_URL = __ENV.BASE_URL || 'http://api-gateway:8080';
export const KEYCLOAK_TOKEN_URL =
    __ENV.KEYCLOAK_URL || 'http://keycloak:8080/realms/revolut/protocol/openid-connect/token';
export const LEDGER_URL = __ENV.LEDGER_URL || 'http://ledger-service:8083';
export const SIMULATOR_URL = __ENV.SIMULATOR_URL || 'http://simulator-service:8089';

// Funded once per test user at setup time - generous headroom so a run's whole transfer volume
// never trips INSUFFICIENT_FUNDS, which would make every subsequent panel about a *different*
// failure mode than the one each script is actually trying to measure.
export const FUND_AMOUNT_MINOR = parseInt(__ENV.FUND_AMOUNT_MINOR || '500000000', 10); // 5,000,000.00

// Every test user shares this password - there is nothing secret about it, these accounts exist
// only for the duration of one load run against a local/throwaway environment.
export const TEST_USER_PASSWORD = 'Password1!';

// Namespaces every run's users so two runs (or a run re-started after a partial failure) never
// collide on email uniqueness, and so `tasks.ps1 psql` can `delete from app_user where email
// like 'k6-%'` to clean up afterwards.
export const RUN_ID = __ENV.K6_RUN_ID || `${Date.now()}`;
