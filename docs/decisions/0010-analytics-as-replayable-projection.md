# 0010. Analytics as a replayable projection

Date: 2026-10-03

## Context

Spend categorization, budgets, and vaults all need a view over money movements that happened in
other services — but `ledger-service` and `card-service` cannot be the ones to maintain that
view (database-per-service, [ADR 0001](0001-microservices-database-per-service.md)), and
`analytics-service` must never become a place bugs in balances can hide, since it isn't allowed
to originate or hold authoritative money.

## Decision

`analytics-service` owns no money and makes no synchronous call into the ledger. It consumes
`ledger.entry.v1` and `card.authorization.v1` — both configured with infinite retention,
specifically so this is possible — and builds its own local projection entirely from that
replayed history. There is no backfill API and no synchronous call to the owning service to
reconstruct state: a fresh consumer (or one recovering from a dropped schema) simply replays both
topics from offset zero and converges on the same projection every time. `tasks.ps1
analytics-rebuild` exercises exactly this: stop the service, drop its schema, reset its consumer
group offsets to zero, restart — Flyway re-migrates, the consumers replay, and the dashboard
comes back identical.

## Consequences

**Good.** A bug in the projection logic is fixable by dropping the schema and replaying, not by
writing a one-off migration that repairs bad aggregate data by hand — the source of truth is the
topic log, not the projection's own table. This was proven, not just designed: `analytics-
rebuild` is a real, runnable command against the live stack, not a theoretical capability.

**Bad.** The two feeder topics' infinite retention is now a hard dependency the whole analytics
feature rests on — if either topic's retention were ever accidentally shortened, a rebuild would
silently produce an incomplete projection with no error, since a consumer replaying from "offset
zero" would actually start from whatever the earliest retained offset happens to be. **Replaying
is also not free of side effects**, and that is the sharper, easier-to-miss cost: rebuilding state
is safe, but a naive consumer that fires a notification on every event it processes would spam a
user with months of history the moment a schema change forces a rebuild. Budget alerts guard
against this explicitly, with `alerted_at_80`/`alerted_at_100` timestamps and a replay-mode flag
that suppresses emission — a projection being "just a replay of the log" is true for the data, but
any side effect riding along with that data has to be deliberately re-architected to not replay
too.
