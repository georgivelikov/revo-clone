# Phase 01 — Identity, Account, Gateway

**Effort:** 3–4 days · **Prerequisites:** [Phase 00](phase-00-foundations.md) green · [← Index](00-overview.md)

## Goal

A person can register, obtain a JWT from Keycloak, and call
`GET /api/accounts/me` through the gateway to see their multi-currency wallets. Three
services exist and the first real Kafka event flows: `identity-service` publishes
`UserRegisteredV1`, `account-service` consumes it and opens wallets. No money moves yet —
balances are all zero and nothing is authoritative. This phase proves the *shape* of the
system: gateway auth, service-to-service choreography, outbox, idempotent consumption.

## Deliverables

```
services/
├── api-gateway/          :8080   Spring Cloud Gateway, JWT validation, routing
├── identity-service/     :8081   db: identity   user profiles, Keycloak Admin API
└── account-service/      :8082   db: account    wallet registry
```

## Design detail

### `identity-service`

Keycloak owns credentials. This service owns everything else about a person.

```sql
create table user_profile (
    id             uuid primary key,           -- mirrors Keycloak's user id
    email          text not null unique,
    first_name     text not null,
    last_name      text not null,
    country        char(2) not null,
    phone          text,
    status         text not null,              -- PENDING | ACTIVE | SUSPENDED
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null
);
```

`POST /api/identity/register` does, in order:

1. Validate the payload.
2. Call `IdentityProviderPort.create(...)`, `setPassword(...)`, `assignRole("customer")` —
   the Keycloak adapter from Phase 00, backed by the `revolut-backend` service account. The
   provider returns the user id.
3. In one local transaction: insert `user_profile` with that id **and** append
   `UserRegisteredV1` to the outbox.
4. Return 201 with the profile.

Register through the port, never against the Keycloak client directly. This is the only place
in the whole system that provisions a user, and keeping the admin API behind the interface is
what lets every test except one run against `InMemoryIdentityAdapter` instead of paying ~15 s
of container startup per test class.

There is an unavoidable seam at step 2→3: the identity provider is an external system with no
participation in your transaction. If step 3 fails, a provider user exists with no profile.
Handle it honestly rather than pretending it can't happen — a reconciliation endpoint
`POST /api/identity/admin/reconcile` that lists provider users without profiles (via
`listAll`) and back-fills them. This is a small, real example of the dual-write problem the
outbox exists to solve, in the one place the outbox cannot help.

Because it is written against `listAll` rather than against Keycloak, the reconcile endpoint
needs no changes if the adapter ever does.

Other endpoints: `GET /api/identity/me`, `PATCH /api/identity/me`.

Events out: `UserRegisteredV1(userId, email, firstName, lastName, country)` on
`identity.user.v1`, keyed by `userId`, compacted.

### `account-service`

Owns the *registry* of wallets — which wallets exist, who owns them, what currency, what
state. It does **not** own balances; the ledger does, from Phase 02. Keeping this boundary
clean now saves a painful refactor later.

```sql
create table account (
    id             uuid primary key,
    user_id        uuid not null,
    currency       char(3) not null,
    type           text not null,              -- WALLET | VAULT
    name           text not null default '',   -- vaults have names; wallets use ''
    state          text not null,              -- ACTIVE | FROZEN | CLOSED
    ledger_account_id uuid,                    -- filled in Phase 02
    created_at     timestamptz not null default now(),
    unique (user_id, currency, type, name)
);
create index on account (user_id);
```

**`name` is `not null default ''`, deliberately.** Postgres treats NULLs as distinct in a
unique constraint, so a nullable `name` would let this constraint permit unlimited duplicate
EUR wallets per user — silently defeating the second half of the redelivery protection that
`UserRegisteredConsumerTest` is written to prove. The empty string collides the way you want.
(A partial unique index on `name IS NULL` is the alternative if you would rather keep NULL
semantics; pick one and test it.)

On `UserRegisteredV1`, open three `WALLET` accounts — EUR, GBP, USD — in one transaction,
guarded by the `processed_event` table so a redelivery does not create duplicates.

Endpoints: `GET /api/accounts/me`, `GET /api/accounts/{id}`,
`POST /api/accounts` (open an additional currency wallet).

Events out: `AccountOpenedV1(accountId, userId, currency, type)` on `account.account.v1`,
keyed by `accountId`, compacted.

**Balances in the response.** Until Phase 02 exists, `GET /api/accounts/me` returns
`balance: null`. Do *not* add a balance column here as a placeholder — it will become the
second source of truth and it will drift. Leave the field null and fill it from the ledger
in Phase 03.

### `api-gateway`

Spring Cloud Gateway, and the only thing the browser talks to.

- Routes: `/api/identity/**` → 8081, `/api/accounts/**` → 8082, and one route per service
  added in later phases.
- `TokenRelay` — validate the JWT at the edge, pass it downstream unchanged. Services
  re-validate; edge validation is a fast reject, not a trust boundary.
- CORS for `http://localhost:5173`.
- Correlation id: mint one if absent, propagate downstream.
- Per-user rate limit via `RequestRateLimiter` backed by Redis, keyed on the JWT subject.
- `/api/identity/register` is the one unauthenticated route.

Leave the `Idempotency-Key` filter out of this phase — it belongs with the first
money-moving endpoint in Phase 03, and building it now means building it against a guess.

## Task list

1. `services/api-gateway` — routes, security, CORS, rate limiter, actuator.
2. `services/identity-service` — Flyway `V1__user_profile.sql` + the shared outbox
   migration, entity, repository, registration via `IdentityProviderPort`, register/me
   endpoints.
3. Wire `common-security`, `common-web`, `common-messaging` into both services and confirm
   the shared Flyway migration applies into each service's own database.
4. Publish `UserRegisteredV1` via the outbox; verify in Kafka UI that the message appears
   on the right partition.
5. `services/account-service` — Flyway, entity, the `UserRegisteredV1` consumer, wallet
   creation, `AccountOpenedV1` publication, read endpoints.
6. Add both services to `docker-compose.yml` (services profile) and confirm they also run
   from the IDE against `docker-compose.infra.yml`.
7. Reconciliation endpoint for orphaned Keycloak users.

## Tests

| Test | Asserts |
|---|---|
| `RegisterUserIntegrationTest` | Testcontainers + Keycloak container: register → profile row + outbox row in one transaction |
| `RegisterRollbackTest` | Forced DB failure after provider create leaves no profile; reconcile endpoint then back-fills it |
| `DuplicateEmailTest` | Second registration with the same email returns 409, creates no provider user |
| `DuplicateWalletConstraintTest` | Two `WALLET` rows for the same user and currency are rejected by the DB — proves the `name` default actually closes the NULL gap |
| `UserRegisteredConsumerTest` | One event → three wallets; **same event twice → still three wallets** |
| `AccountQueryTest` | `GET /accounts/me` returns only the caller's accounts, never another user's |
| `GatewayAuthTest` | No token → 401; valid token → 200; token for user A cannot read user B's account (403/404) |
| `GatewayRateLimitTest` | Burst past the limit returns 429 |

`UserRegisteredConsumerTest`'s redelivery case is the one that matters — it is the first
real proof the idempotent-consumer wrapper from Phase 00 actually works.

## Verification

```powershell
.\tasks.ps1 up
mvn -q -pl services/identity-service,services/account-service,services/api-gateway -am spring-boot:run  # or run from IDE

# register
curl -s -X POST http://localhost:8080/api/identity/register `
  -H "Content-Type: application/json" `
  -d '{"email":"carol@demo.io","password":"demo","firstName":"Carol","lastName":"Smith","country":"IE"}'

# token
$tok = (curl -s -X POST http://localhost:8180/realms/revolut/protocol/openid-connect/token `
  -d "client_id=revolut-web&grant_type=password&username=carol@demo.io&password=demo" | ConvertFrom-Json).access_token

# wallets
curl -s http://localhost:8080/api/accounts/me -H "Authorization: Bearer $tok"
```

Expected: three wallets (EUR, GBP, USD), `state: ACTIVE`, `balance: null`. In Kafka UI,
`identity.user.v1` has one message and `account.account.v1` has three, and the
`account-service` consumer group shows zero lag.

## Done when

- [ ] Register → token → `GET /accounts/me` returns three wallets, through the gateway
- [ ] `UserRegisteredV1` is published via the outbox, never a direct send
- [ ] Redelivering that event creates no duplicate wallets
- [ ] Unauthenticated requests are rejected at the gateway
- [ ] User A cannot read user B's accounts
- [ ] Correlation id appears in both services' logs for one request
- [ ] Both services run in Docker *and* from the IDE against the infra compose file

## Risks & gotchas

- **Keycloak Admin API is fiddly.** The service account needs `realm-management/manage-users`
  on the *target realm*, not the master realm. Budget half a day for this alone; it is the
  main reason Phase 01 is not a one-day phase.
- **Testcontainers + Keycloak is slow.** ~15 s startup per test class. Run everything except
  `IdentityProviderPortContractTest` and `RegisterUserIntegrationTest` against
  `InMemoryIdentityAdapter`, and share a single static container (`@Container static`) across
  the two that need the real thing.
- **The gateway starter was renamed in Spring Cloud 2025.0** and stays renamed in 2025.1. Use
  `spring-cloud-starter-gateway-server-webflux`, plus the OAuth2 client starter, or
  `TokenRelay` is silently missing and you get downstream 401s despite a valid token at the
  edge. The old `spring-cloud-starter-gateway` coordinates may not resolve at all on this
  train — they were deprecated in 2025.0 with a warning, which is the usual prelude to
  removal.
- **Gateway property prefixes moved too**, to `spring.cloud.gateway.server.webflux.*`. Route
  configuration copied from any older tutorial will bind to nothing, start cleanly, and route
  nothing — which looks exactly like a routing bug. `spring-boot-properties-migrator` on the
  test classpath will tell you.
- **CORS is configured in exactly one place** — the gateway. Adding it in each service too
  produces duplicate `Access-Control-Allow-Origin` headers, which browsers reject.
- **Do not add balance columns to `account`.** This is the single most likely place to
  create a second source of truth and undo the ledger's guarantees before it even exists.

## Variant — walking skeleton (considered and rejected)

Recorded because the reasoning is worth keeping, not because it is still on the table.

The alternative was to replace this phase with: gateway + account-service only, a hardcoded
dev JWT issuer instead of Keycloak, a fake in-memory balance, one Kafka round-trip, and a
bare React page showing a balance that changes when you POST to it — then swapping in
Keycloak and the real ledger at Phases 02–03. Perhaps a day of code thrown away, in exchange
for a working system in three days instead of two weeks.

**Rejected** because the stated goal is distributed-systems practice rather than shipping
speed, and the day discarded is precisely the Keycloak integration this phase already budgets
half a day for. The underlying problem it solves is real, though — wanting something on
screen before week three — and the 03a/03b split in the index addresses it without the
rework: 03a ends with a working, curl-verifiable transfer system, and 03b puts it on screen.
