# Phase 02 — The Ledger

**Effort:** 5–7 days · **Prerequisites:** [Phase 01](phase-01-identity-account-gateway.md) green · [← Index](00-overview.md)

## Goal

`ledger-service` exists and is correct. It records immutable double-entry journal entries,
maintains a balance cache in the same transaction, supports reservations (holds), refuses
to create or destroy money under any circumstance, and survives concurrent and duplicate
requests. No UI, no payments — just a provably correct money core with a test suite you
trust.

This is the most important phase in the project. Everything after it assumes the ledger is
right. Do not rush it, and do not move on with a failing concurrency test.

## Deliverables

```
services/ledger-service/     :8083   db: ledger
```

## Design detail

### Why double-entry

A single `balance` column that you increment and decrement has no audit trail, no way to
answer "why is this number what it is", and no way to detect corruption. Double-entry makes
every movement a pair of equal and opposite postings, so the sum of all postings in a
currency is always exactly zero. That single invariant is a continuous, system-wide
integrity check: if it holds, money was neither created nor destroyed.

### Schema

```sql
create table ledger_account (
    id          uuid primary key,
    owner_ref   text not null,            -- 'user:<uuid>' | 'system:fx-pool' | ...
    type        text not null,            -- USER_WALLET | VAULT | FX_POOL
                                          -- CARD_SETTLEMENT | FEE_INCOME | FX_PNL
                                          -- EXTERNAL_CLEARING
    currency    char(3) not null,
    created_at  timestamptz not null default now(),
    unique (owner_ref, type, currency)
);

create table journal_entry (
    id              uuid primary key,
    idempotency_key text not null unique,
    type            text not null,        -- TRANSFER | FX | CARD_SETTLEMENT | FEE | ADJUSTMENT | REVERSAL
    reference_id    uuid,                 -- the payment/authorization that caused it
    reverses_entry_id uuid references journal_entry(id),
    metadata        jsonb not null default '{}',
    created_at      timestamptz not null default now()
);

create table posting (
    id                uuid primary key,
    entry_id          uuid not null references journal_entry(id),
    ledger_account_id uuid not null references ledger_account(id),
    direction         text not null check (direction in ('DEBIT','CREDIT')),
    amount_minor      bigint not null check (amount_minor > 0),
    currency          char(3) not null,
    reservation_id    uuid references reservation(id),   -- set when this posting captures a hold
    seq               int not null,
    unique (entry_id, seq)
);
create index on posting (ledger_account_id, id);

create table account_balance (
    ledger_account_id uuid primary key references ledger_account(id),
    available_minor   bigint not null default 0,
    reserved_minor    bigint not null default 0 check (reserved_minor >= 0),
    currency          char(3) not null,
    version           bigint not null default 0        -- @Version, optimistic lock
);

create table reservation (
    id                uuid primary key,
    ledger_account_id uuid not null references ledger_account(id),
    amount_minor      bigint not null check (amount_minor > 0),
    currency          char(3) not null,
    state             text not null,      -- HELD | CAPTURED | RELEASED | EXPIRED
    reference_id      uuid not null,
    idempotency_key   text not null unique,
    expires_at        timestamptz not null,
    created_at        timestamptz not null default now(),
    resolved_at       timestamptz
);
create index on reservation (state, expires_at);
```

Plus `processed_event` and `outbox_event` from `common-messaging`.

Declare `reservation` **before** `posting` in the migration — `posting.reservation_id` is a
forward reference otherwise and the migration will not apply.

`amount_minor > 0` always: direction is carried by `direction`, never by a negative amount.
Allowing signed amounts *and* a direction column gives you two ways to express the same
thing and guarantees they will disagree eventually.

**`journal_entry.idempotency_key` is globally unique, so it must be constructed server-side,
never passed through from a client.** Callers derive it from the thing that caused the entry —
`payment:<paymentId>`, `fxquote:<quoteId>`, `cardauth:<networkRef>` — and the ledger rejects
anything that does not match a known prefix pattern. Forwarding a client's raw
`Idempotency-Key` header here would be a real bug: payment-service scopes those per user
(`unique (initiator_user_id, idempotency_key)`), so two users choosing the same key would
collide in this table, and the loser would silently receive the other user's journal entry
instead of an error.

### The balancing invariant

Enforced twice, deliberately:

1. **In code** — `PostingEngine` validates before inserting: at least two postings, and for
   each currency in the entry, `sum(debits) == sum(credits)`.
2. **In the database** — a deferred constraint trigger on `posting`, checked at commit:

   ```sql
   create constraint trigger posting_balanced
   after insert on posting
   deferrable initially deferred
   for each row execute function assert_entry_balanced();
   ```

   The function groups the entry's postings by currency and raises if any group is
   non-zero. Deferred is essential — postings are inserted one at a time and the entry is
   only balanced once they are all in.

The DB check exists because application code eventually gets bypassed — by a migration, a
support script, a bug. The trigger is the thing that actually holds.

### Accounting sign convention

Pick one and write it at the top of `PostingEngine`:

> **A user wallet is a liability of the bank to the customer. A CREDIT increases the
> customer's money; a DEBIT decreases it.**

So a transfer from Alice to Bob is `DEBIT alice_wallet` / `CREDIT bob_wallet`. Getting this
backwards is the single most common bug in ledger implementations, and it is invisible
until you look at a statement. Put it in a comment, put it in the README, and name the test
`DebitDecreasesCustomerBalanceTest`.

### Balance maintenance and concurrency

`account_balance` is a cache. It is updated in the **same transaction** as the postings,
using JPA optimistic locking on `version`. On a version conflict, retry the whole operation
up to 3 times with a small backoff.

Why optimistic rather than `SELECT ... FOR UPDATE`: pessimistic locking on a hot account
serialises everything and deadlocks across multi-account entries unless you sort lock
acquisition by account id. Optimistic locking pushes the conflict to commit time and makes
the retry explicit and observable. Under the Phase-08 load test you can watch the retry
counter and reason about contention. Either is defensible; the point is to choose
deliberately and know why.

Available balance must never go negative for `USER_WALLET` and `VAULT` types. System
accounts (`FX_POOL`, `FEE_INCOME`, `EXTERNAL_CLEARING`) are allowed to go negative —
that's what makes them the counterparty.

The check is `available_minor - reserved_minor >= amount` on debit. Reserved funds are
already spoken for.

### Balance column semantics — write this down before writing any code

`account_balance` has two columns and postings and reservations both move money between them.
**Which column a posting moves depends on whether it is capturing a hold**, and leaving that
implicit is the single easiest way to corrupt this service:

| Posting | Balance effect |
|---|---|
| DEBIT, `reservation_id IS NULL` | `available -= amount` |
| CREDIT, `reservation_id IS NULL` | `available += amount` |
| DEBIT, `reservation_id` set | **`reserved -= amount` only** — the money already left `available` at hold time |

The third row is the one that matters. If a capture's posting also decremented `available`,
hold-then-capture would take the money out twice: once at `hold`, once at the posting. And
this failure is *invisible to the zero-sum invariant* — the postings themselves still balance
perfectly, so the headline safety check of this whole phase passes while every reserved
payment quietly destroys money in the balance cache. Only the reconciliation job would catch
it, and only if its query is right (see below).

Every hold→capture path in the project runs through this seam: FX quote execution in Phase
04, card authorization settlement in Phase 05, round-ups in Phase 06. Get it wrong here and
you will be debugging it there.

Put the table above in a comment at the top of `PostingEngine`, next to the sign convention.

### Reservations

A reservation moves money from available to reserved without a posting. It is not an
accounting event — it is a promise.

| Operation | Effect |
|---|---|
| `hold` | `available -= amount`, `reserved += amount`, state `HELD`, `expires_at` set |
| `capture` | post the journal entry **with `reservation_id` set**, which decrements `reserved` and leaves `available` untouched; state `CAPTURED` |
| `release` | `reserved -= amount`, `available += amount`; state `RELEASED` |
| expiry sweep | `@Scheduled` job releases `HELD` rows past `expires_at`; state `EXPIRED` |

Note that `hold` and `release` conserve `available + reserved` exactly, while `capture` is the
only operation that reduces the total — which is correct, because it is the only one that
corresponds to real money movement.

Capture may be for **less** than the hold (a restaurant tip adjustment, a partial shipment);
the difference is released. Build this now — retrofitting partial capture later touches
everything.

### API

Internal, called by other services over Kafka *and* exposed over HTTP for queries:

| Endpoint | Purpose |
|---|---|
| `POST /internal/ledger/entries` | post a balanced entry (idempotent) |
| `POST /internal/ledger/reservations` | hold |
| `POST /internal/ledger/reservations/{id}/capture` | capture, full or partial |
| `POST /internal/ledger/reservations/{id}/release` | release |
| `GET /internal/ledger/accounts/{id}/balance` | current balance |
| `GET /internal/ledger/accounts/{id}/postings` | paginated statement |

`/internal/**` is not routed by the gateway — these are service-to-service only. In Phase 03
a thin read-through on the gateway exposes balances to the UI.

### Events out

`ledger.entry.v1`, keyed by `ledgerAccountId` (one message per affected account, so each
account's stream is ordered and independently consumable):

- `EntryPostedV1(entryId, accountId, direction, amount, currency, balanceAfter, type, referenceId)`
- `EntryRejectedV1(idempotencyKey, accountId, reason, referenceId)` — reasons:
  `INSUFFICIENT_FUNDS`, `ACCOUNT_FROZEN`, `ACCOUNT_NOT_FOUND`, `CURRENCY_MISMATCH`
- `ReservationHeldV1` / `ReservationCapturedV1` / `ReservationReleasedV1`

### Reconciliation

A `@Scheduled` job (nightly, plus an admin endpoint to trigger it) that recomputes every
balance from `sum(postings)` and compares against `account_balance`:

```sql
select la.id,
       coalesce(sum(case when p.direction = 'CREDIT' then p.amount_minor
                         else -p.amount_minor end), 0)      as computed,
       ab.available_minor + ab.reserved_minor               as cached
from ledger_account la
join account_balance ab on ab.ledger_account_id = la.id
left join posting p     on p.ledger_account_id = la.id
group by la.id, ab.available_minor, ab.reserved_minor
having coalesce(sum(case when p.direction = 'CREDIT' then p.amount_minor
                         else -p.amount_minor end), 0)
       <> ab.available_minor + ab.reserved_minor;
```

**It must compare against `available + reserved`, not `available` alone.** A hold moves money
between the two columns without creating a posting, so `available` on its own drifts from the
posting sum by the amount of every live reservation — and from Phase 05 onward most accounts
have one during normal operation. Comparing against `available` would make this job cry wolf
on every card authorization, and a reconciliation alert that fires constantly is one you will
mute, which is strictly worse than not having built it.

Any drift is logged at ERROR, emits a
`LedgerDriftDetectedV1` event, and increments a Micrometer counter. In Phase 08 that
counter goes on the dashboard; for now, the log line is the alerting story and that is
honest — a learning project does not need PagerDuty, but it does need the drift to be
*visible* rather than silent.

Build an admin endpoint that deliberately corrupts a balance so you can watch the job catch
it. A safety net you have never seen fire is not a safety net.

### Portability boundary

This service is the least portable code in the project, and deliberately so — abstracting to
lowest-common-denominator SQL would mean giving up the deferred constraint trigger, which is
the thing that actually holds. Aurora and RDS are Postgres, so on the stated target this
costs nothing. What it does need is for the dependency to be *enumerable* rather than
scattered, so a future evaluation is a checklist:

| Feature | Used by | Portable? |
|---|---|---|
| `DEFERRABLE INITIALLY DEFERRED` constraint trigger | the balanced-entry invariant | **No** — MySQL has no deferred constraints at all |
| `FOR UPDATE SKIP LOCKED` | outbox poller, multi-replica safety | Postgres 9.5+, MySQL 8+, SQL Server via `READPAST` |
| `jsonb` | outbox payload, `journal_entry.metadata` | Degrades to `text` with manual serialisation |
| `INSERT … ON CONFLICT … excluded` | analytics aggregates (Phase 06) | Dialect-specific syntax, same semantics |
| `timestamptz` | everywhere | Portable |

Native SQL lives in exactly four places across the project — the outbox poller (Phase 00),
the balanced trigger migration, the reconciliation query above, and the Phase 06 aggregate
upserts. Everything else goes through Spring Data. Keep it that way and the table above stays
true; let `SKIP LOCKED` spread into three services and it stops being a checklist.

Migrations run as an ordinary table owner, never a superuser — managed Postgres does not
grant one. The constraint trigger is created by the role that owns the table, which is
allowed; `CREATE EXTENSION` generally is not.

## Task list

1. Scaffold `ledger-service`, Flyway `V1__ledger.sql` with the schema above.
2. `V2__balanced_trigger.sql` — the deferred constraint trigger and its function.
3. Domain: `LedgerAccount`, `JournalEntry`, `Posting`, `AccountBalance`, `Reservation`.
4. `PostingEngine` — validate, insert, update balances per the balance-column semantics
   table, append outbox. One transaction. Put that table and the sign convention in a comment
   at the top of the class.
5. Optimistic-lock retry wrapper (`@Retryable` on `ObjectOptimisticLockingFailureException`).
6. `ReservationService` — hold / capture (full and partial) / release / expiry sweep.
7. System account bootstrap: a Flyway migration seeding `FX_POOL`, `FEE_INCOME`, `FX_PNL`,
   `EXTERNAL_CLEARING` per supported currency.
8. Consume `AccountOpenedV1` → create the matching `ledger_account` and zero balance, then
   publish `LedgerAccountCreatedV1` so `account-service` can fill `ledger_account_id`.
9. HTTP endpoints for entries, reservations and queries.
10. Reconciliation job + admin trigger + deliberate-drift endpoint.
11. A funding endpoint (`EXTERNAL_CLEARING` → wallet) so later phases have money to move.

## Tests

| Test | Asserts |
|---|---|
| `BalancedEntryTest` | Unbalanced entry is rejected by code **and** by the trigger (test the trigger directly with raw SQL) |
| `DebitDecreasesCustomerBalanceTest` | Sign convention, explicitly |
| `InsufficientFundsTest` | Debit beyond available is rejected; balance unchanged; `EntryRejectedV1` emitted |
| `ReservedFundsAreNotAvailableTest` | Hold then attempt to spend the held amount → rejected |
| `PartialCaptureTest` | Capture < hold; remainder released; balances exact |
| `ReservationExpiryTest` | Sweep releases an expired hold and restores available |
| `IdempotentEntryTest` | Same `idempotency_key` twice → one entry, one set of postings, same response |
| `CaptureDoesNotDoubleDecrementTest` | Hold 10.00 then capture 10.00 → `available` dropped by exactly 10.00 (not 20.00) and `reserved` is 0 |
| **`ConcurrentDebitTest`** | 100 threads debit 1.00 from an account holding 50.00 → see below |
| `ConcurrentMixedTest` | Parallel debits and credits on one account → final balance equals the arithmetic sum |
| `ReconciliationDetectsDriftTest` | Corrupt a balance via raw SQL → job reports exactly that account |
| `MultiCurrencyEntryTest` | An entry touching two currencies balances per-currency, not overall |
| `ZeroSumInvariantTest` | After an arbitrary sequence of operations, `sum(postings)` per currency is 0 |

**`ConcurrentDebitTest` — assert what the design actually guarantees.** The obvious assertion
is "exactly 50 succeed, 50 rejected", and it is wrong: with optimistic locking capped at 3
retries, some threads legitimately exhaust their retries under 100-way contention and fail
with a lock exception rather than `INSUFFICIENT_FUNDS`. Written that way the test is flaky by
construction, and you would go hunting a ledger bug that does not exist.

Assert instead:

- successes ≤ 50, and `successes == 50` only when no thread exhausted its retries;
- final balance == `50.00 − successes × 1.00`, exactly;
- `available_minor` never observed negative at any point;
- every failure classified as either `INSUFFICIENT_FUNDS` or lock-exhaustion, with both
  counts reported in the assertion message.

That set is deterministic. With it in place the original advice holds and is the gate for the
phase: run it 50 times in a loop, and if it is *still* flaky, the ledger is wrong — flakiness
then is a correctness problem, not a test problem.

If lock-exhaustion failures are common enough to be annoying, that is a signal about the
retry policy rather than the test: consider retrying with a deadline instead of a fixed
attempt count, and note the decision.

## Verification

```powershell
mvn -pl services/ledger-service verify

# fund an account, then read it back
curl -s -X POST http://localhost:8083/internal/ledger/entries `
  -H "Content-Type: application/json" -H "Idempotency-Key: fund-001" `
  -d '{"type":"ADJUSTMENT","postings":[
        {"accountRef":"system:external-clearing:EUR","direction":"DEBIT","amountMinor":100000,"currency":"EUR"},
        {"accountRef":"user:<alice-uuid>:WALLET:EUR","direction":"CREDIT","amountMinor":100000,"currency":"EUR"}]}'

# replay the same call — must return the same entry id, not a second entry
curl -s -X POST ... -H "Idempotency-Key: fund-001" ...

curl -s http://localhost:8083/internal/ledger/accounts/<id>/balance
curl -s -X POST http://localhost:8083/internal/ledger/admin/reconcile
```

Then, in psql, the invariant by hand:

```sql
select currency,
       sum(case when direction='CREDIT' then amount_minor else -amount_minor end)
from posting group by currency;
-- every row must be exactly 0
```

## Done when

- [ ] `ConcurrentDebitTest` passes 50 consecutive runs
- [ ] Unbalanced entries are rejected by the DB trigger, proven with raw SQL
- [ ] Duplicate `Idempotency-Key` returns the original entry, creates nothing new
- [ ] Ledger idempotency keys are server-derived; a client-supplied key is rejected
- [ ] Reservations hold, capture (full and partial), release and expire correctly
- [ ] Hold-then-capture decrements `available` exactly once, proven by a named test
- [ ] Reconciliation compares against `available + reserved` and is quiet on a healthy system
      with live reservations
- [ ] Zero-sum invariant holds per currency after the full test suite
- [ ] Reconciliation detects deliberately injected drift
- [ ] A wallet can be funded and its balance read back
- [ ] Sign convention is documented in code and in the README

## Risks & gotchas

- **The capture double-decrement is the bug to fear most in this phase.** It passes the
  zero-sum invariant, passes every single-operation test, and only shows up as balances that
  are quietly too low once Phase 04 or 05 starts capturing holds — by which point you will be
  debugging the wrong service. `CaptureDoesNotDoubleDecrementTest` exists specifically to
  catch it, and it is worth writing before `PostingEngine` rather than after.
- **Deferred triggers and JPA flush order.** Hibernate may flush at unexpected points; the
  trigger must be `DEFERRABLE INITIALLY DEFERRED` or a perfectly valid entry will be
  rejected mid-insert.
- **`@Transactional` + `@Retryable` ordering.** The retry must wrap *outside* the
  transaction, or you retry inside a transaction already marked rollback-only. Put the retry
  on a caller-facing method that delegates to the transactional one.
- **Optimistic lock retries under the concurrency test** will look like failures in the logs.
  Count them as a metric rather than logging each at WARN, or the test output is unreadable.
- **`bigint` overflow** is not a real risk for money (9.2 × 10¹⁸ minor units) but *is* a real
  risk if you ever `sum()` postings across the whole table in a test with generated data.
- **Don't let the ledger know about payments, cards or FX.** It takes balanced entries and
  reservations. The moment it grows a `transferMoney(from, to)` convenience method, business
  logic starts leaking in and the boundary is gone.
- **Time.** This phase is where the schedule slips. The tests are the deliverable as much as
  the code; budget roughly half the phase for them.
