# Phase 03 — Payments and the First UI

**Effort:** 6–8 days, split into 03a + 03b · **Prerequisites:** [Phase 02](phase-02-ledger.md) green · [← Index](00-overview.md)

## Goal

Two people, two browser windows. Alice sends Bob €10. Bob's balance updates without a
refresh. Behind that: a saga across three services, an idempotency layer that survives a
double-click and a network retry, and a WebSocket push. This is the phase where the project
stops being infrastructure and becomes a product.

**This is really two phases and should be run as two.** It is the largest unit of work in the
project — a hand-written saga, three-layer idempotency, WebSocket ticket auth, *and* the
entire React foundation — and treating it as one 6–8 day block is where a solo schedule
usually starts slipping without anyone noticing.

| | Scope | Done when |
|---|---|---|
| **03a** | `payment-service`, the saga, idempotency, `notification-service` backend | The transfer works end to end, verified with curl and the Testcontainers suite |
| **03b** | `web/`, WebSocket client, optimistic UI, Playwright | Two browsers, live transfer, no refresh |

03a is a legitimate stopping point that leaves a coherent, demonstrable system. Task list
items 1–6 below are 03a; 7–12 are 03b.

## Deliverables

```
services/
├── payment-service/       :8084   db: payment    transfer orchestration
└── notification-service/  :8088   db: notification  STOMP over WebSocket
web/                       :5173   React 19 + TS + Vite — login, Home, Transfer
```

## Design detail

### The transfer saga (same currency)

```
1.  POST /api/payments        Idempotency-Key: <uuid>
    payment-svc: validate → Payment(PENDING) + outbox PaymentInitiatedV1     [one tx]
    → 202 Accepted, { paymentId, status: PENDING }

2.  ledger-svc consumes PaymentInitiatedV1
    → posts one journal entry: DEBIT sender wallet / CREDIT receiver wallet  [one tx]
    → emits EntryPostedV1  (or EntryRejectedV1 with a reason)

3.  payment-svc consumes the result
    → PENDING → COMPLETED, or PENDING → FAILED(reason)
    → outbox PaymentCompletedV1 / PaymentFailedV1

4.  notification-svc consumes payment + ledger events
    → pushes over STOMP to /user/{userId}/queue/events for both parties
```

The API returns **202, not 201**. The transfer is accepted, not completed. This is the
honest contract for an asynchronous system and it forces the UI to handle the pending state
properly rather than pretending the write was synchronous.

There is no compensation step in the same-currency case: the ledger either posts the whole
entry or none of it, in one local transaction. Compensation appears in Phase 04 when FX adds
a second resource. Resist adding a saga framework here — the choreography is four events
and a state machine, and writing it by hand is the point.

### `payment-service`

```sql
create table payment (
    id                uuid primary key,
    idempotency_key   text not null,
    initiator_user_id uuid not null,
    source_account_id uuid not null,
    target_account_id uuid not null,
    amount_minor      bigint not null check (amount_minor > 0),
    currency          char(3) not null,
    status            text not null,     -- PENDING | COMPLETED | FAILED | CANCELLED
    failure_reason    text,
    reference         text,              -- user's note
    ledger_entry_id   uuid,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null,
    unique (initiator_user_id, idempotency_key)
);
create index on payment (source_account_id, created_at desc);
```

State machine, enforced explicitly — an illegal transition throws rather than silently
updating:

```
PENDING → COMPLETED | FAILED | CANCELLED
COMPLETED, FAILED, CANCELLED are terminal
```

Endpoints:

| Endpoint | Notes |
|---|---|
| `POST /api/payments` | requires `Idempotency-Key`; 202 |
| `GET /api/payments/{id}` | poll fallback for the UI |
| `GET /api/payments` | paginated feed, filterable by account |
| `GET /api/payments/recipients` | recent + searchable counterparties |

Validation before accepting: source account belongs to the caller, both accounts exist and
are `ACTIVE`, currencies match, amount positive and under a per-transaction cap. Account
state comes from a **local read model** in `payment-service`, built by consuming
`account.account.v1` — not a synchronous call to `account-service`. This is the first place
the "no sideways HTTP" rule does real work, and it is worth doing properly: the compacted
topic means a fresh instance rebuilds its view on startup.

### Idempotency, all three layers

1. **Edge (gateway + Redis).** A filter on money-moving POSTs: key on
   `(jwt.sub, Idempotency-Key)`. First request stores a marker with a 24 h TTL; a concurrent
   duplicate gets 409 `IN_PROGRESS`; a completed duplicate replays the stored response body
   and status verbatim. Store the request-body hash too and return 422 if the same key
   arrives with a different body — that is a client bug and silently returning the old
   response hides it.
2. **Service (Postgres).** `unique (initiator_user_id, idempotency_key)`. Catch the
   constraint violation and return the existing payment. This is the layer that actually
   guarantees correctness; Redis is an optimisation and may be flushed at any time.
3. **Consumer (Postgres).** `processed_event` from Phase 00, already in use.

**Do not forward the client's `Idempotency-Key` to the ledger.** It is scoped per user here,
but `journal_entry.idempotency_key` is globally unique — so two users picking the same key
would collide there, and one would silently receive the other's journal entry. Derive the
ledger key from the payment instead: `payment:<paymentId>`. Same rule for every other caller
of the ledger, in every later phase.

Belt and braces on purpose. Redis alone is not durable enough for money; the DB constraint
alone means every duplicate does real work before being rejected.

### `notification-service`

- STOMP over SockJS-free native WebSocket at `/ws`.
- Consumes `payment.event.v1`, `ledger.entry.v1`, `notification.push.v1`; fans out to
  `/user/{userId}/queue/events`.
- Persists notifications so the UI can show a history and an unread badge.

**WebSocket authentication — the gotcha.** A browser cannot set an `Authorization` header on
a WebSocket handshake. Three options; pick the second:

1. Token in the query string — it lands in access logs. No.
2. **Ticket endpoint.** `POST /api/notifications/ticket` (normal JWT auth) returns a
   single-use, 30-second, random ticket stored in Redis. The client opens
   `ws://.../ws?ticket=...`; the handshake interceptor exchanges it for the user id and
   deletes it. Short-lived, single-use, never logged in a reusable form.
3. Token in the STOMP `CONNECT` frame — works, but the socket is open and unauthenticated
   until the first frame, which complicates the timeout story.

Budget half a day for this. It is the most fiddly non-obvious piece in the phase.

Scaling note: with one instance, in-memory STOMP routing is fine. With N instances a user's
socket may be on a different instance than the consumer that received the event, so you need
a shared relay. The cheap answer is a Redis pub/sub channel between instances; the proper
answer is an external STOMP broker. Phase 07 revisits this — for now, document the
limitation rather than pretending it isn't there.

**This is the project's only stateful service, and that is a deployment constraint, not just
a scaling one.** In-memory sessions mean this service cannot run more than one replica
anywhere — laptop, ECS or Kubernetes. Every other service is stateless and horizontally
scalable from the day it is written. Phase 07 removes the limitation with the Redis fan-out;
until then, treat "notification-service is pinned to one instance" as a known constraint with
a date on it rather than an accident.

### Frontend

Stack: Vite + React 19 + TypeScript + Tailwind v4 + TanStack Query + Zustand +
`react-oidc-context` (PKCE) + Framer Motion + `@stomp/stompjs`.

Layout: a max-width 430 px column centred on desktop, dark theme, bottom tab bar — so it
reads like the phone app.

Screens this phase:

| Screen | Contents |
|---|---|
| Login | Keycloak redirect (PKCE), silent renew, logout |
| Home | Balance header, currency pills, recent activity feed |
| Transfer | Recipient → amount → review → confirm → result |
| Activity detail | Status, timestamps, the ledger entry behind it |

**Optimistic UI with reconciliation** — the piece worth building carefully. On confirm:

1. Generate the `Idempotency-Key` **client-side** (a UUID) and keep it for retries. This is
   what makes a retry idempotent — a server-generated key would defeat the whole mechanism.
2. Insert a local `PENDING` row into the TanStack Query cache immediately; animate it in.
3. POST. On 202, keep it pending.
4. The WebSocket event flips it to `COMPLETED` (settle animation) or `FAILED` (roll back,
   show the reason).
5. If no event arrives within ~5 s, fall back to polling `GET /api/payments/{id}`. Sockets
   drop; the UI must not be the only thing holding the truth.

This makes eventual consistency a visible product behaviour instead of a hidden flaw, which
is exactly the lesson.

Also build the **dev drawer** (dev builds only): a slide-over showing the live event stream
for the current user with correlation ids. It costs an hour and it is the best debugging
tool you will have for the rest of the project.

## Task list

**03a — backend**

1. `payment-service` scaffold, Flyway, `Payment` entity, explicit state machine.
2. Local account read model in `payment-service` from `account.account.v1`.
3. `POST /api/payments` → PENDING + outbox, in one transaction; 202 response. Ledger key
   derived as `payment:<paymentId>`, never the client's header.
4. Ledger consumer for `PaymentInitiatedV1`; result consumer in `payment-service`.
5. Gateway idempotency filter with Redis, including the body-hash check.
6. `notification-service`: STOMP config, ticket endpoint, handshake interceptor, fan-out.

**03b — frontend**

7. `web/` scaffold, Tailwind, OIDC, app shell, bottom tab bar, design tokens.
8. Home screen wired to `GET /api/accounts/me` + ledger balances.
9. Transfer flow with client-generated idempotency key and optimistic insert.
10. WebSocket client with reconnect/backoff and the 5 s polling fallback.
11. Dev drawer.
12. Add both services and `web` to `docker-compose.yml`.

## Tests

| Test | Asserts |
|---|---|
| `TransferHappyPathTest` | Testcontainers end-to-end: POST → PENDING → COMPLETED; both balances correct |
| `TransferInsufficientFundsTest` | Payment ends FAILED with the reason; **sender's balance unchanged** |
| `DoubleSubmitTest` | Same key twice concurrently → one payment, one ledger entry, identical responses |
| `SameKeyDifferentBodyTest` | Returns 422, does not replay the old response |
| `PaymentStateMachineTest` | COMPLETED → FAILED throws |
| `CrossUserAuthTest` | Cannot send from an account you do not own |
| `LedgerEventRedeliveryTest` | `EntryPostedV1` twice → payment completed once, no double-count |
| `WebSocketTicketTest` | Valid ticket connects; reused ticket rejected; expired ticket rejected |
| `ZeroSumAfterTransfersTest` | After 1000 random transfers, per-currency posting sum is 0 |
| Playwright `transfer.spec.ts` | Two browser contexts; A sends to B; B's balance updates with no reload |

## Verification

```powershell
docker compose up -d
.\tasks.ps1 seed        # alice and bob, each funded with 500.00 EUR
cd web; npm run dev
```

1. Open <http://localhost:5173> in a normal window as `alice@demo.io`, and in a private
   window as `bob@demo.io`.
2. Alice sends Bob €10 with a note.
3. Alice's feed shows it pending, then completed — Bob's balance and feed update live.
4. Stop `ledger-service`, send another €10. It stays PENDING. Start the ledger again; it
   completes on its own without any user action. **This is the money shot of the whole
   phase** — the system is durable across a service outage because the event is on the log.
5. Double-click confirm rapidly: exactly one payment appears.
6. Send €10,000 with a €500 balance: FAILED, `INSUFFICIENT_FUNDS`, balance untouched.
7. Kill the browser's network for 10 s: the socket reconnects and the feed reconciles.

## Done when

- [ ] Two browsers, live transfer, no refresh
- [ ] Killing `ledger-service` mid-transfer delays but does not lose the payment
- [ ] Double-submit produces exactly one payment
- [ ] Insufficient funds fails cleanly with the sender's balance untouched
- [ ] WebSocket auth uses single-use tickets, no token in any URL
- [ ] Polling fallback works with the socket forcibly disabled
- [ ] Zero-sum invariant still holds after the load of the E2E suite
- [ ] Playwright two-context test passes in CI-like conditions

## Risks & gotchas

- **This is the biggest phase, which is why it is split.** Run 03a to green before starting
  03b rather than interleaving them — debugging a saga through a half-built UI is
  substantially harder than debugging it with curl.
- **STOMP `/user/` destinations** need `setUserDestinationPrefix` and a `Principal` resolved
  during the handshake. Silent non-delivery is the usual symptom of getting this wrong.
- **CORS on the WebSocket upgrade** is configured separately from HTTP CORS. Expect one
  round of confusion.
- **TanStack Query optimistic updates** need care on rollback: use `onMutate` to snapshot
  and `onError` to restore, and reconcile by payment id, not array position.
- **Keycloak PKCE redirect loop** in dev, usually from a trailing-slash mismatch between the
  configured redirect URI and Vite's dev URL.
- **Do not let `payment-service` call `account-service` over HTTP** to validate accounts.
  The local read model is more work up front and it is the architecture you said you wanted.
- **Seed script must be idempotent** — you will run it dozens of times.
