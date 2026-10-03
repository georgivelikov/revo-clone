# 0003. Transactional outbox over direct Kafka publishing

Date: 2026-10-03

## Context

A request that writes to Postgres and then needs to tell the rest of the system about it (via
Kafka) has no way to make both actions atomic — there is no two-phase commit spanning a
database and a broker in any practical deployment of either technology. A direct publish inside
a `@Transactional` method can succeed while the surrounding transaction later rolls back
(publishing an event for a write that never happened), or the transaction can commit while the
publish call itself fails (a write that happened but nothing downstream ever hears about).

## Decision

Business code never talks to Kafka directly. It calls `OutboxAppender.append(...)` inside its
own transaction, which inserts a row into that service's `outbox_event` table — ordinary data,
committed or rolled back exactly like every other write in the transaction. A separate poller
(`OutboxPublisher`) runs on a fixed delay, selects unpublished rows with
`FOR UPDATE SKIP LOCKED`, publishes each to Kafka, and marks it published. `SKIP LOCKED` is what
makes this safe to run on every replica of a service simultaneously — two pollers racing for the
same row simply have one of them skip it, rather than double-publish or deadlock.

## Consequences

**Good.** The local database transaction is the only thing that has to be atomic, and it already
is — Postgres guarantees that. A service can crash between committing the row and the poller
picking it up, and the row is simply still there, unpublished, when it restarts: nothing is
lost. The same mechanism scales horizontally for free, since `SKIP LOCKED` needs no external
coordination between poller instances.

**Bad, and the project's own clearest example of a decision's cost actually showing up under
load.** Publish latency is bounded by the poll interval, not zero — and phase 08's load testing
found this is not a minor footnote: the outbox poller's fixed-interval, fixed-batch-size design
was the *actual* throughput bottleneck at scale, not the HikariCP pool size or optimistic-lock
contention the project's own design doc had predicted. At 100 transfers/second, the backlog of
unpublished rows grew without bound for as long as load held, because the poller's throughput
ceiling (~100 rows/sec per service) sat right at or below sustained demand. Adaptive polling
(task 08.9) closed most of that gap, but the structural fact remains: this pattern trades
latency and a fixed throughput ceiling for the atomicity guarantee, and that trade has to be
sized against real load, not assumed away. It also means delivery is only ever at-least-once —
every consumer downstream of an outbox topic must be idempotent on its own terms
([ADR 0006](0006-idempotency-at-three-layers.md)), because a poller retry after a partial
publish failure can and will redeliver.
