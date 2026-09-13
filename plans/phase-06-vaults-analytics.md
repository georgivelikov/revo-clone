# Phase 06 — Vaults, Budgets and Analytics

**Effort:** 4–6 days · **Prerequisites:** [Phase 05](phase-05-cards.md) green · [← Index](00-overview.md)

## Goal

Spending becomes legible. Card transactions are categorised automatically, a monthly
dashboard shows where the money went, budgets warn before they are blown, and spare change
from every card purchase rounds up into a savings vault. Architecturally this is the
project's first pure **read model** — a service that consumes the event log, owns no
authoritative money, and can be deleted and rebuilt from scratch.

## Deliverables

```
services/analytics-service/   :8087   db: analytics   projections, categories, budgets
web/  Vaults screen + Analytics dashboard
```

## Design detail

### Analytics is a projection, not a source of truth

`analytics-service` consumes `ledger.entry.v1` and `card.authorization.v1` and writes
aggregates. It never writes to the ledger. The test of whether you have built it correctly:
**drop its entire database, replay the topics from offset 0, and get byte-identical
results.** Phase 07 automates that as a one-command rebuild; design for it now by making
every projection a pure function of the event stream with no hidden state.

That constraint has a consequence worth internalising: no `now()` anywhere in a projection.
A projection that reads the wall clock is not replayable. Bucket by `occurredAt` from the
event, always.

### Vaults

A vault is a `VAULT`-type ledger account. Moving money in or out is an ordinary journal
entry between two accounts the same user owns. No new money mechanics at all — which is the
point, and a good sign the Phase 02 model was right.

```sql
create table vault (
    id                uuid primary key,
    user_id           uuid not null,
    account_id        uuid not null,       -- the VAULT account
    name              text not null,
    currency          char(3) not null,
    goal_minor        bigint,
    roundup_enabled   boolean not null default false,
    roundup_source_card_id uuid,
    created_at        timestamptz not null default now()
);
```

Note `vault` lives in `analytics-service` but the *account* lives in `account-service` and
the *money* in `ledger-service`. If that split feels awkward, it is worth sitting with —
it is the real cost of microservices, and feeling it is more instructive than reading about
it. (A defensible alternative is a small `vault-service`; a worse one is letting analytics
write to the ledger.)

### Round-ups

On each **settled** card posting (never on the authorization — you would round up a
transaction that might reverse):

```
spend 4.37 EUR → round to 5.00 → transfer 0.63 EUR from wallet to vault
```

Emitted as a normal transfer through `payment-service`, so it flows through the same saga,
appears in the feed, and cannot bypass the ledger's rules. Multiplier options (1×, 2×, 5×)
and a rule that a round-up which would overdraw is skipped silently rather than failing.

Skipping silently is the right product behaviour and a nice small lesson: the round-up is a
best-effort side effect of a primary action, and it must never be able to fail that action.

### Categorisation

Three layers, first match wins:

1. **User override** — `merchant_name` → category, set by the user. Highest priority and
   remembered for future transactions from that merchant.
2. **Merchant rules** — a seeded table of name patterns (`TESCO*` → Groceries).
3. **MCC mapping** — the fallback, from the ISO MCC on the authorization.

```sql
create table transaction_category (
    authorization_id  uuid primary key,
    user_id           uuid not null,
    category          text not null,
    source            text not null,       -- USER | MERCHANT_RULE | MCC | DEFAULT
    assigned_at       timestamptz not null
);
```

Categories: Groceries, Restaurants, Transport, Shopping, Entertainment, Bills, Health,
Travel, Transfers, Cash, Income, Other.

A user override must **retro-apply** to that merchant's past transactions and update the
affected monthly aggregates. That is a small but real "rebuild a slice of the projection"
exercise, and a good warm-up for the full replay in Phase 07.

### Aggregates and budgets

```sql
create table monthly_spend (
    user_id     uuid not null,
    month       date not null,            -- first of month
    category    text not null,
    currency    char(3) not null,
    total_minor bigint not null,
    txn_count   int not null,
    primary key (user_id, month, category, currency)
);

create table budget (
    id            uuid primary key,
    user_id       uuid not null,
    category      text not null,
    month         date not null,
    limit_minor   bigint not null,
    currency      char(3) not null,
    alerted_at_80 timestamptz,
    alerted_at_100 timestamptz,
    unique (user_id, category, month, currency)
);
```

Aggregates update incrementally on each event (an upsert with `total_minor = total_minor +
excluded.total_minor`), not by re-scanning. On crossing 80% or 100%, emit to
`notification.push.v1` — and use the `alerted_at_*` columns as the idempotency guard so a
replay does not re-notify the user for six months of history. **This is the trap in replayable
projections**: rebuilding state is safe, re-firing side effects is not. Notifications must be
suppressed during replay, either by that timestamp guard or by a replay-mode flag on the
consumer.

Multi-currency is genuinely awkward here: a user spending in EUR, GBP and USD has no single
"total". Store per-currency, and present with a `reportingCurrency` converted at the
month-end rate, clearly labelled as indicative. Do not silently sum across currencies.

### Frontend

**Vaults screen** — list with progress rings toward goals, create/edit, move money in and
out, round-up toggle with multiplier, a "round-ups this month" total.

**Analytics screen** — month selector; donut by category with a centred total; tap a slice to
drill into that category's transactions; a 6-month trend bar chart; budget bars that turn
amber at 80% and red at 100%; "top merchants" list.

Use the `dataviz` guidance for the charts — consistent categorical colours across the donut,
the trend chart and the budget bars, so a category is the same colour everywhere. Inconsistent
colour mapping between charts is the fastest way to make a dashboard unreadable.

## Task list

1. `analytics-service` scaffold, Flyway, consumers for ledger and card topics.
2. MCC → category seed data; merchant rules table + seeds; user overrides.
3. `monthly_spend` incremental upsert projection, bucketed on `occurredAt`.
4. Retro-apply on user override, with aggregate correction.
5. Budgets, threshold detection, idempotent alerting.
6. Vault CRUD; move-in/move-out via `payment-service`.
7. Round-up rule on settled card postings, with overdraw skip.
8. Query endpoints: monthly summary, category drill-down, trend, top merchants.
9. Vaults screen.
10. Analytics dashboard with the chart set.
11. Seed script extension: 3 months of plausible history so the dashboard is not empty.

## Tests

| Test | Asserts |
|---|---|
| `CategorisationPriorityTest` | User override beats merchant rule beats MCC |
| `RetroApplyTest` | Override updates past transactions and corrects aggregates exactly |
| `RoundUpTest` | 4.37 → 0.63 to vault; 5.00 → no round-up (already whole) |
| `RoundUpSkipsOnInsufficientFundsTest` | Overdrawing round-up is skipped; the card settlement is unaffected |
| `RoundUpOnSettlementOnlyTest` | An authorization alone triggers nothing; a reversal never rounds up |
| `BudgetAlertTest` | Crossing 80% then 100% fires once each, never twice |
| `ReplayDoesNotRealertTest` | Full topic replay reproduces aggregates and sends **zero** notifications |
| `ProjectionRebuildTest` | Drop DB, replay from offset 0 → identical aggregates |
| `NoWallClockInProjectionTest` | Events with old `occurredAt` land in historical months |
| `MultiCurrencySpendTest` | Per-currency totals stay separate; no implicit summing |
| `VaultTransferTest` | Move to vault is a normal ledger entry; zero-sum preserved |

`ProjectionRebuildTest` and `ReplayDoesNotRealertTest` are the two that matter — they are
the phase's real architectural content.

## Verification

```powershell
.\tasks.ps1 seed --with-history     # 3 months of card activity
curl -s -X POST http://localhost:8089/simulator/cards/spend/batch?count=50
curl -s -X POST http://localhost:8089/simulator/clock/advance-day
```

In the UI: the Analytics donut fills, categories look sensible, the trend chart shows three
months. Set a €200 Restaurants budget, spend past €160 → an amber push arrives. Enable
round-ups on a vault, spend €4.37, advance the clock → €0.63 lands in the vault and appears
in the feed as a transfer.

Then the phase's real test:

```powershell
.\tasks.ps1 analytics-rebuild    # drops the analytics DB and replays from offset 0
```

The dashboard comes back identical, and no notification is sent.

## Done when

- [ ] Transactions categorise automatically with sensible results
- [ ] A user override retro-applies and corrects historical aggregates
- [ ] Round-ups fire on settlement only, and skip rather than fail when short
- [ ] Budget alerts fire once per threshold
- [ ] Analytics DB can be dropped and fully rebuilt from Kafka
- [ ] A rebuild sends no notifications
- [ ] No projection code reads the wall clock
- [ ] Multi-currency totals are never silently summed
- [ ] Charts use one consistent colour per category across all three views

## Risks & gotchas

- **Side effects during replay** is the defining hazard of this phase. Every emit in a
  projection consumer needs a guard. Build the replay flag early, not after you have spammed
  yourself with 400 notifications.
- **Incremental aggregates drift** if an event is processed twice. The `processed_event`
  guard from Phase 00 is load-bearing here in a way it was not before — an idempotent
  *balance* update is naturally idempotent; an incremental *sum* is not.
- **Retro-apply is O(transactions for that merchant)** and will be slow on a large history.
  Do it asynchronously via an event and show "updating…" in the UI.
- **Topic retention must be infinite** for `ledger.entry.v1` and `card.authorization.v1`, or
  replay-from-zero silently produces partial results. Set it in Phase 00's topic creation and
  verify it here before relying on it.
- **Scope creep risk is highest in this phase** — it is the one with the most obvious "just
  one more feature" surface (merchants, insights, subscriptions detection, forecasts). The
  architecture lesson is the replayable projection; everything beyond that is product polish
  and can wait for Phase 09.
