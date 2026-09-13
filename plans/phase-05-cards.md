# Phase 05 — Cards

**Effort:** 4–6 days · **Prerequisites:** [Phase 04](phase-04-fx-multicurrency.md) green · [← Index](00-overview.md)

## Goal

Issue a virtual card, spend on it at a simulated merchant, and watch the money go through
the real lifecycle: an **authorization** that holds funds, then a **settlement** a day later
that actually moves them. Freeze the card and watch the next auth decline. The gap between
auth and settlement is the thing almost every clone gets wrong, and it is the whole point of
this phase.

## Deliverables

```
services/
├── card-service/       :8086   db: card   issuance, authorization decisions, limits
└── simulator-service/  :8089   extended: card network + merchants + a clock you control
web/  Card screen — virtual card, reveal, freeze, limits, spend feed
```

## Design detail

### Why auth and settlement are separate

When you tap a card, the merchant asks the issuer "will you cover €42?". The issuer says yes
and **holds** the money — no accounting has happened, the merchant has no money, and your
available balance drops. Hours or days later the merchant submits a batch and the money
actually moves, possibly for a *different* amount (tips, fuel, hotels) or never (the auth
expires).

Modelling this correctly gives you: pending vs settled transactions in the feed, available
vs total balance, and a real use for the reservation machinery from Phase 02.

### `card-service`

```sql
create table card (
    id                uuid primary key,
    user_id           uuid not null,
    account_id        uuid not null,          -- funding wallet
    pan_hash          bytea not null,         -- HMAC-SHA256 of the PAN; the lookup key
    pan_last4         char(4) not null,       -- display only
    pan_encrypted     bytea not null,         -- AES-GCM, key from env
    expiry_month      int not null,
    expiry_year       int not null,
    cvv_encrypted     bytea not null,
    type              text not null,          -- VIRTUAL | PHYSICAL
    state             text not null,          -- ACTIVE | FROZEN | CANCELLED
    label             text,
    created_at        timestamptz not null default now()
);
create unique index on card (pan_hash);
create index on card (user_id);

create table card_limit (
    card_id       uuid not null references card(id),
    period        text not null,              -- DAILY | MONTHLY | PER_TRANSACTION
    amount_minor  bigint not null,
    currency      char(3) not null,
    primary key (card_id, period)
);

create table card_authorization (
    id              uuid primary key,
    card_id         uuid not null references card(id),
    network_ref     text not null unique,     -- the network's id, our idempotency anchor
    merchant_name   text not null,
    merchant_mcc    char(4) not null,
    merchant_country char(2) not null,
    amount_minor    bigint not null,
    currency        char(3) not null,
    billing_minor   bigint,                   -- after FX, in the wallet's currency
    billing_currency char(3),
    state           text not null,            -- APPROVED | DECLINED | SETTLED
                                              -- REVERSED | EXPIRED | PARTIALLY_SETTLED
    decline_reason  text,
    reservation_id  uuid,
    ledger_entry_id uuid,
    authorized_at   timestamptz not null default now(),
    settled_at      timestamptz
);
create index on card_authorization (card_id, authorized_at desc);
```

**Uniqueness is on `pan_hash`, not on last4.** The authorization flow resolves a card by PAN
hash, so that column has to exist — and it is also the only correct uniqueness constraint.
A unique index on `(pan_last4, expiry_month, expiry_year)` is global across all users and
draws from only 10,000 last-4 values, so card issuance would start failing with spurious
collisions well before the pool of real PANs was anywhere near exhausted. Use a keyed HMAC
rather than a bare hash, so the hash column is not itself a brute-forceable index of card
numbers (the search space of a PAN is small enough to matter).

**PAN handling.** Encrypt with AES-GCM using a key from an environment variable, store only
`last4` in clear, and return the full PAN from exactly one endpoint that requires a step-up.
This is not PCI compliance and this plan is not pretending it is — it is the minimum shape
so the code does not teach you a habit you would have to unlearn. Never log the PAN; add a
logback masking converter and a test that asserts a PAN never appears in log output.

### Authorization flow

```
simulator → card.authorization.v1  AuthorizationRequestedV1
            { networkRef, pan, amountMinor, currency, merchant… }

card-service:
  1. resolve card by PAN hash          → not found        → DECLINED(CARD_NOT_FOUND)
  2. state == ACTIVE?                  → frozen           → DECLINED(CARD_FROZEN)
  3. expiry in the future?             → expired          → DECLINED(CARD_EXPIRED)
  4. FX if merchant currency ≠ wallet  → fx-service rate, mark up
  5. limits: per-transaction, rolling daily, rolling monthly
                                       → exceeded         → DECLINED(LIMIT_EXCEEDED)
  6. ledger: HOLD billing amount, 7-day expiry
                                       → insufficient     → DECLINED(INSUFFICIENT_FUNDS)
  7. → AuthorizationApprovedV1   (or AuthorizationDeclinedV1 with the reason)

notification-service → instant push, exactly like the real app
```

Steps 1–6 must complete in well under a second — a real network times out around 2–5 s.
Worth measuring in Phase 08 even though nothing here enforces it, because it is a genuine
constraint that shapes the design: this is why the decision cannot wait on a downstream
service that might be slow.

**Ordering matters.** Keyed by `cardId`, so two taps on the same card are evaluated in order
against the same limits. This is the clearest possible justification for the partition-key
rule from Phase 00 — with random partitioning, two simultaneous auths could both pass a
limit check that only one should.

### Settlement

`simulator-service` submits settlements on a clock you control (an endpoint that advances a
simulated day, rather than waiting 24 hours):

| Case | Handling |
|---|---|
| Exact settlement | Capture the full hold → journal entry `CARD_SETTLEMENT` |
| Partial (< auth) | Capture the lesser amount, release the difference |
| Over-settlement (tip) | Capture the hold, post the excess directly — may overdraw; allow it and flag |
| Reversal | Release the hold, no posting |
| Expiry (7 days, no settlement) | Sweep releases the hold |

The posting is `DEBIT user wallet / CREDIT system:card-settlement`. Over-settlement is worth
building: it is the one path that can legitimately push a wallet negative, and it forces you
to confront the fact that "never negative" is a policy, not a law.

### Merchant simulator

A catalogue of ~30 merchants with realistic MCCs (5411 groceries, 5812 restaurants, 4121
rideshare, 5999 misc), each with a plausible amount range and currency. Endpoints:

| Endpoint | Purpose |
|---|---|
| `POST /simulator/cards/spend` | one auth with a chosen or random merchant |
| `POST /simulator/cards/spend/batch?count=N` | N auths over a date range — feed material |
| `POST /simulator/clock/advance-day` | settle everything older than the cutoff |
| `POST /simulator/cards/reverse/{networkRef}` | reversal |

Foreign-currency merchants are essential — they exercise the card→FX path and produce the
"you spent $50, we charged you €46.12" line the UI needs.

### Frontend — Card screen

- The card itself: gradient face, `•••• 4242`, holder name, expiry. Tap to flip and reveal
  the full PAN — requires a step-up (re-enter passcode; in this project, a modal confirming
  against a stored PIN), and the revealed PAN auto-hides after 30 s and is never put in
  clipboard history without an explicit action.
- Freeze toggle with an instant, animated state change.
- Limits editor.
- Per-card spend feed with **pending clearly distinct from settled** — this is the visible
  payoff of the whole phase. Pending rows show a dashed border and "Pending"; on settlement
  they animate into the settled style with the final amount.
- Merchant logos/initials, category icons, foreign-currency original amount as a subline.

## Task list

1. `card-service` scaffold, Flyway, encryption helper, keyed `pan_hash` derivation, PAN
   generation (valid Luhn, test BIN).
2. Issuance endpoint + `CardIssuedV1`; reveal endpoint behind step-up.
3. Authorization consumer with the full decision chain, keyed by `cardId`.
4. Limit evaluation using a rolling window over `card_authorization`.
5. FX path for foreign-currency merchants, with the card mark-up applied separately from
   the exchange spread.
6. Settlement consumer: full, partial, over-settlement, reversal.
7. Ledger: `CARD_SETTLEMENT` entry type; hold expiry sweep at 7 days.
8. `simulator-service`: merchant catalogue, spend endpoints, controllable clock.
9. Freeze/unfreeze, limits endpoints.
10. Card screen, reveal + step-up, freeze, limits, spend feed with pending/settled states.
11. Logback PAN masking + a test asserting it.

## Tests

| Test | Asserts |
|---|---|
| `AuthorizationApprovedTest` | Hold created; available drops; total unchanged |
| `AuthorizationDeclinedFrozenTest` | Frozen card declines; no hold |
| `LimitExceededTest` | Daily limit enforced across multiple auths in the window |
| `ConcurrentAuthLimitTest` | Two simultaneous auths on one card cannot both pass a limit only one should |
| `SettlementExactTest` | Hold captured; posting created; available and total now agree |
| `SettlementPartialTest` | Lesser capture; difference released; balances exact |
| `OverSettlementTest` | Excess posted; wallet may go negative; flagged, not silently swallowed |
| `ReversalTest` | Hold released; no posting; balance restored |
| `AuthExpiryTest` | Unsettled hold released after 7 simulated days |
| `ForeignCurrencyAuthTest` | USD merchant on a EUR wallet: FX applied, mark-up in `FEE_INCOME` |
| `DuplicateNetworkRefTest` | Same `networkRef` twice → one authorization |
| `CardLookupByPanHashTest` | Auth resolves the right card by PAN hash; two cards sharing last4 and expiry both issue and both resolve correctly |
| `PanNeverLoggedTest` | Full log capture across an auth contains no PAN |
| `ZeroSumAfterCardActivityTest` | After 200 auths + settlements, every currency sums to 0 |

## Verification

```powershell
# issue a card in the UI, then spend on it
curl -s -X POST http://localhost:8089/simulator/cards/spend `
  -H "Content-Type: application/json" `
  -d '{"cardId":"<id>","merchant":"Tesco","amountMinor":4250,"currency":"EUR"}'
```

Watch in the UI: a push arrives instantly, the transaction appears as **Pending**, available
balance drops but total does not. Then:

```powershell
curl -s -X POST http://localhost:8089/simulator/clock/advance-day
```

The pending row animates into settled, available and total converge, and the ledger now has
a posting where before there was only a reservation. Freeze the card and spend again — an
instant decline with the reason.

```sql
-- the phase in one query: money held but not yet moved
select c.pan_last4, ca.merchant_name, ca.state, ca.amount_minor, ca.settled_at
from card_authorization ca join card c on c.id = ca.card_id
order by ca.authorized_at desc limit 20;
```

## Done when

- [ ] Auth holds funds without creating a posting
- [ ] Available and total balance visibly differ while an auth is pending
- [ ] Settlement creates the posting and reconciles the two figures
- [ ] Partial settlement releases the remainder correctly
- [ ] Reversal restores the balance with no posting ever created
- [ ] Frozen card declines instantly
- [ ] Daily limits hold under two concurrent auths
- [ ] Foreign-currency spend converts and shows both amounts in the UI
- [ ] PAN appears in no log line anywhere
- [ ] Zero-sum invariant holds after heavy card activity

## Risks & gotchas

- **Rolling-window limits are subtly hard.** "Daily" means a rolling 24 h or a calendar day
  in the user's timezone — pick one, write it down, and test the boundary. Calendar-day is
  more intuitive for users; rolling is easier to implement correctly.
- **Holds and the FX rate.** The hold is in the wallet currency at the auth-time rate, but
  settlement may arrive days later at a different rate. Decide: hold at auth rate and settle
  at auth rate (simple, what most issuers do for the *held* amount) or re-rate at settlement
  (realistic for some schemes). Document the choice; re-rating means the capture amount
  differs from the hold and you must handle the difference.
- **`networkRef` is your idempotency key** for the whole card flow. The network will
  genuinely resend. Unique-constrain it and test the redelivery.
- **Over-settlement breaking the never-negative rule.** The ledger must allow *this specific*
  path to overdraw, which means the rule is a policy parameter on the operation, not a hard
  constraint on the account type. Get this right or you will be tempted to weaken the
  constraint globally, which undoes Phase 02.
- **Don't build real PCI.** Encrypt, mask, step-up, move on. It is a learning project and the
  goal is the shape, not a certification.
- **The clock endpoint is essential.** Without it you cannot test settlement without waiting
  a real day, and you will end up not testing it.
