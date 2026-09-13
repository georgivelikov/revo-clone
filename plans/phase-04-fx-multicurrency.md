# Phase 04 — FX and Multi-Currency

**Effort:** 4–6 days · **Prerequisites:** [Phase 03](phase-03-payments-and-ui.md) green · [← Index](00-overview.md)

## Goal

Rates tick live on screen. A user exchanges €100 to GBP at a quoted rate that expires in 30
seconds, and the books balance to exactly zero in *both* currencies. Cross-currency P2P
transfers work. This is Revolut's signature feature and the first place a saga needs a real
compensation path.

## Deliverables

```
services/
├── fx-service/         :8085   db: fx    rates, quotes, spread
└── simulator-service/  :8089   db: —     rate feed (card network added in Phase 05)
web/  Exchange screen with a live ticking rate
```

## Design detail

### Rate feed

`simulator-service` publishes a random-walk mid-rate for each supported pair to
`fx.rate.tick.v1` about twice a second, keyed by pair, with 1 hour retention. A random walk
(rather than a static table) matters: it makes quote expiry meaningful and makes the
"rate moved between quote and confirm" case reachable in testing.

```java
record FxRateTickV1(String pair, BigDecimal mid, Instant asOf, String source) {}
// pair is always alphabetical: "EUR/GBP", never "GBP/EUR"
```

Canonical pair ordering with inversion for the reverse direction. Storing both directions
independently guarantees they drift apart and produce arbitrage within your own system.

Pairs: EUR/GBP, EUR/USD, GBP/USD, plus EUR/JPY specifically because JPY has **zero** minor
digits and will catch every place the code assumes two.

### `fx-service`

```sql
create table fx_rate (
    pair        text not null,
    mid         numeric(20,10) not null,
    as_of       timestamptz not null,
    source      text not null,
    primary key (pair, as_of)
);

create table fx_quote (
    id              uuid primary key,
    idempotency_key text not null unique,
    user_id         uuid not null,
    sell_currency   char(3) not null,
    buy_currency    char(3) not null,
    sell_minor      bigint not null,
    buy_minor       bigint not null,
    mid_rate        numeric(20,10) not null,
    client_rate     numeric(20,10) not null,   -- mid ± spread
    spread_bps      int not null,
    reservation_id  uuid,                      -- hold on the sell account
    state           text not null,             -- ACTIVE | CONSUMED | EXPIRED | CANCELLED
    expires_at      timestamptz not null,
    created_at      timestamptz not null default now()
);
create index on fx_quote (state, expires_at);
```

Keeps the latest rate per pair in memory (updated by the Kafka consumer) and writes a
periodic sample to `fx_rate` for history. Reads for quoting come from memory; a restart
replays the tail of the topic to warm up.

**Quoting.** `POST /api/fx/quotes { sellCurrency, buyCurrency, sellMinor | buyMinor }`:

1. Look up mid.
2. Apply spread (default 50 bps; make it configurable so you can demo the revenue line).
3. Compute the counter-amount with `FxMath.convert` — `RoundingMode.HALF_EVEN`, scaled to
   the target currency's digits.
4. **Reserve** the sell amount on the user's wallet via the ledger, 30 s expiry.
5. Persist `ACTIVE` and return the quote with `expiresAt`.

Reserving (rather than only locking a rate) is deliberate: without it, a user can take five
quotes against one balance and execute them all. It also gives the compensation path
something real to compensate.

**Executing.** `POST /api/fx/quotes/{id}/execute`:
- Quote must be `ACTIVE` and unexpired — check server-side, never trust the client's clock.
- Emits `FxExecutionRequestedV1` via the outbox; the ledger captures the reservation and
  posts the entry.

An expiry sweep marks stale quotes `EXPIRED` and releases their reservations.

### The FX posting

A currency exchange is not one entry with two postings. Money leaves one currency and
appears in another, and the ledger's invariant is **per currency** — so both currencies must
balance independently, through a pool account that acts as the counterparty.

It is five legs, not four. The pool gives up the **mid-rate** amount; the difference between
mid and the client rate is the spread, and it is posted explicitly as revenue:

```
Entry type: FX     Alice sells 100.00 EUR, buys 85.43 GBP
                   mid 0.8600, spread 50 bps, client rate 0.8543

  DEBIT   alice:EUR        10000   EUR      ← Alice loses EUR
  CREDIT  system:fx-pool   10000   EUR      ← pool gains EUR
  ─────────────────────────────────────── EUR nets to zero ✓

  DEBIT   system:fx-pool    8600   GBP      ← pool gives up the MID amount
  CREDIT  alice:GBP         8543   GBP      ← Alice gains GBP at the CLIENT rate
  CREDIT  system:fee-income   57   GBP      ← the spread, as revenue
  ─────────────────────────────────────── GBP nets to zero ✓
```

A sub-unit rounding residual adds a sixth leg to `FX_PNL`. `FxMath` returns both the
converted amount and the residual so this is not a guess.

**Why the fee leg is not optional.** Draw this as four legs at the client rate and it still
balances — but the spread then disappears into the pool as drift, which is exactly what the
`FX_POOL` balance is worst at telling you about. Posting it explicitly means `FEE_INCOME`
answers "how much has the spread earned" directly, and `FX_POOL` means only "what is our
currency exposure". Those are two different questions and conflating them is how you end up
unable to answer either. `SpreadIsRevenueTest` and `FxEntryBalanceTest` are asserting exactly
this distinction.

The pool is deliberately unbalanced *in value* across currencies — it is long EUR and short
GBP. That is exactly what an FX book is, and it is why `FX_POOL` is allowed a negative
balance while user wallets are not.

For a **cross-currency P2P transfer**, the same shape with Bob's wallet as the final credit —
still one journal entry, still one transaction, so there is no partial state to compensate
inside the ledger.

### Compensation

This is the first phase with a genuine compensation path, because the quote's reservation
lives in the ledger while the quote state lives in `fx-service`:

| Failure | Compensation |
|---|---|
| Ledger rejects the capture (funds moved after the hold) | `fx-service` marks the quote `CANCELLED`, releases the reservation, emits `FxExecutionFailedV1` |
| Quote expires before execute | Sweep releases the reservation |
| `fx-service` crashes between reserve and persist | The reservation's own 30 s expiry releases it — the ledger's timeout is the backstop |

That last row is the useful lesson: the reservation expiry means the ledger does not depend
on `fx-service` ever coming back. Design every hold with a timeout owned by the resource,
not by the orchestrator.

### Frontend — Exchange screen

- Two currency selectors with flags, amount field on either side (bidirectional).
- The rate ticks live from the WebSocket — visibly moving, with a subtle flash on change.
- "Get quote" freezes it and starts a 30 s countdown ring.
- On expiry the confirm button disables and offers a refresh — do not silently re-quote at a
  new rate, which is how real apps get accused of sharp practice.
- Confirm → optimistic pending → settled, same reconciliation pattern as Phase 03.
- Show the spread explicitly: "You get 85.43 GBP · rate 0.8543 · includes 0.50% fee".

## Task list

1. `simulator-service` scaffold + random-walk rate producer with configurable volatility.
2. `fx-service` scaffold, Flyway, rate consumer, in-memory latest-rate cache with
   replay-on-startup.
3. `FxMath` extension returning `(converted, residual)`.
4. Quote creation: spread, conversion, ledger reservation, persistence.
5. Quote execution: outbox event, ledger capture, the five-leg posting (six with a residual).
6. Expiry sweep for quotes and their reservations.
7. Ledger: `FX` entry type, pool/fee/PnL accounts per currency, negative-balance allowance
   for system accounts.
8. `payment-service`: cross-currency transfers — obtain a quote, then the same saga with
   compensation on rejection.
9. `notification-service`: forward rate ticks to subscribed clients (throttled to ~2/s).
10. Exchange screen, countdown, live rate, confirm flow.
11. Extend the seed script with GBP/USD/JPY balances.

## Tests

| Test | Asserts |
|---|---|
| `FxConversionRoundingTest` | HALF_EVEN at exact boundaries; residual always accounted for |
| `JpyZeroDigitTest` | EUR→JPY produces whole yen; no 1/100-yen anywhere |
| `InversePairConsistencyTest` | EUR/GBP then GBP/EUR round-trips within spread — no arbitrage |
| `FxEntryBalanceTest` | FX entry nets to zero **per currency**, across all five or six legs |
| `SpreadIsRevenueTest` | `FEE_INCOME` grows by exactly the spread; `FX_PNL` absorbs only residual; the pool holds only the mid-rate exposure |
| `QuoteExpiryTest` | Executing an expired quote → 409; reservation released |
| `QuoteReservesFundsTest` | Two quotes against one balance: second fails on insufficient available |
| `ConcurrentQuoteExecuteTest` | Same quote executed twice concurrently → one entry |
| `CrossCurrencyTransferTest` | Alice EUR → Bob GBP; both balances correct, books balance |
| `FxServiceDownTest` | Kill `fx-service` after reserve; reservation expires and funds return |
| `RateReplayOnStartupTest` | Restarted `fx-service` warms its cache from the topic tail |
| `ZeroSumMultiCurrencyTest` | After 500 random exchanges, every currency sums to 0 |

## Verification

```powershell
docker compose up -d
# watch rates flow
docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server localhost:9092 --topic fx.rate.tick.v1 --from-beginning | Select-Object -First 20
```

In the UI: open Exchange, watch the rate move, take a quote, let it expire without touching
it — confirm disables and the held funds reappear as available. Take another and confirm:
EUR drops, GBP appears, both feeds update live.

Then in psql, the invariant that proves the whole phase:

```sql
select currency,
       sum(case when direction='CREDIT' then amount_minor else -amount_minor end) as net
from posting group by currency;
-- every currency: exactly 0

select la.type, la.currency, ab.available_minor
from account_balance ab join ledger_account la on la.id = ab.ledger_account_id
where la.type in ('FX_POOL','FEE_INCOME','FX_PNL') order by 1,2;
-- FX_POOL long in one currency, short in the other; FEE_INCOME positive
```

## Done when

- [ ] Rates tick visibly in the UI, driven by Kafka
- [ ] A quote expires and releases its reservation without manual intervention
- [ ] FX entries balance to zero per currency, verified in SQL
- [ ] Spread lands in `FEE_INCOME`, not in pool drift; only sub-unit residual in `FX_PNL`
- [ ] The pool leg is posted at the mid rate, the user leg at the client rate
- [ ] EUR→JPY produces whole yen
- [ ] Cross-currency P2P transfer works end to end
- [ ] Killing `fx-service` mid-flow returns the funds via reservation expiry
- [ ] No arbitrage: round-tripping a pair never yields more than you started with

## Risks & gotchas

- **`numeric(20,10)` vs `BigDecimal` scale.** Hibernate will happily truncate on write. Set
  the scale explicitly on the entity and assert it in a test.
- **Rate ticks will flood the WebSocket.** Throttle server-side to ~2/s per client and send
  only changed pairs, or the browser spends its time re-rendering.
- **Clock skew between quote issue and execute.** Always validate expiry against the server
  clock. A client-side countdown is presentation only.
- **The inverse-pair trap.** `1/0.8543` is not exactly the GBP/EUR rate once spread is
  applied in both directions. Derive one direction from the other and apply spread once.
- **Reservation expiry racing execution.** Execute must re-check the reservation is still
  `HELD` inside the capture transaction, not before it.
- **Don't let the FX pool net to zero.** If it does, you have almost certainly posted the
  conversion as a single two-leg entry and broken per-currency balancing.
- **`FEE_INCOME` staying at zero** means you posted the pool leg at the client rate instead
  of the mid rate. The entry balances, every test about balancing passes, and the spread is
  silently sitting in pool drift. Check `FEE_INCOME` explicitly after the first exchange.
- **The capture path runs through Phase 02's balance-column rule.** Executing a quote
  captures a reservation, so its posting carries a `reservation_id` and must not decrement
  `available` a second time. If EUR balances come out doubly reduced after an exchange, the
  bug is in `PostingEngine`, not here.

## Variant — removing the synchronous quote call (deferred to Phase 07, deliberately)

**Decision: build the synchronous version here; refactor it in Phase 07 as an explicit
exercise.** The rest of this section is why.

As written, `payment-service` calls `fx-service` over HTTP for a quote on a cross-currency
transfer, behind a Resilience4j circuit breaker. That is the one sideways HTTP call in the
system and it is a deliberate, defensible compromise: the user must see a firm rate before
confirming.

The alternative: `fx-service` publishes rates to a **compacted** topic; `payment-service`
materialises a local rate view from it and quotes without any network call. No sideways
HTTP anywhere, and you learn local state stores — the pattern behind Kafka Streams' KTables.
Costs perhaps a day extra and duplicates spread logic in two services unless you extract it
into `common-money`.

A good middle path is to build the synchronous version here, then refactor it in Phase 07 as
an explicit exercise — you get to feel why the coupling hurts (it shows up the moment you
kill `fx-service` and transfers start failing) before removing it.
