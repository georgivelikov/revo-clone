# 0005. Optimistic locking over pessimistic on balances

Date: 2026-10-03

## Context

Two concurrent operations can touch the same `account_balance` row at once — two transfers into
the same wallet, a transfer landing while a card authorization holds funds on the same account.
Something has to arbitrate the race. The two standard options are a pessimistic lock
(`SELECT ... FOR UPDATE`, holding the row for the duration of the transaction) or an optimistic
one (a `version` column, write fails if it changed since read, caller retries).

## Decision

Optimistic locking, with a bounded retry (three attempts) in `PostingEngine` around the balance
update. No posting ever holds a row lock across a network call or an external API — the lock, if
any, is held only for the duration of a local, in-process write.

## Consequences

**Good.** Under the load this system was actually measured at, the choice turned out not to
matter at all in the common case: phase 08's hotspot experiment found **zero** optimistic-lock
retries across 60,000 transfers at single-instance scale, because a lone sequential Kafka
consumer already serializes every write to a given partition by construction — the mechanism
that exists specifically to arbitrate concurrent writers never had concurrent writers to
arbitrate. Scaled to three `ledger-service` instances genuinely contending for the same hot
account, it activated exactly as designed: a 6.7% retry rate, still 100% success, no user-visible
failure. A pessimistic lock would have paid a cost (connection held, potential lock queueing)
on every single write, including the overwhelming majority that were never actually contended.

**Bad.** The cost of contention, when it happens, shows up as tail latency rather than as nothing
happening — p95 climbed from 22ms to 83ms and the maximum observed latency reached 959ms at
three-instance scale under the deliberately adversarial single-account hotspot load, since each
retry re-runs the 20ms/40ms backoff `PostingEngine` uses. A workload with sustained, heavy
contention on a small number of accounts (not represented in this project's demo data, which
spreads activity across many accounts) would see retries exhausted and a user-visible failure
more often than a pessimistic design would, which simply queues instead of retrying and giving
up. The choice assumes contention is the exception, not the norm — which held for every load
profile this project actually tested, but is an assumption, not a guarantee.
