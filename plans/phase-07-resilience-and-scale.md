# Phase 07 — Resilience and Scale

**Effort:** 4–5 days · **Prerequisites:** [Phase 06](phase-06-vaults-analytics.md) green · [← Index](00-overview.md)

## Goal

Break the system on purpose and prove it heals. Kill services mid-saga, poison a topic with
an unparseable message, partition the network, scale a consumer group from 1 to 3 instances
and back. Nothing should lose money, duplicate money, or require manual repair. This is the
phase where the project stops being a demo and starts being an argument that you understand
distributed systems.

## Deliverables

No new services. Instead: dead-letter handling, retry topics, circuit breakers, a chaos
control plane, replay tooling, and a documented set of failure experiments with results.

```
platform/common-messaging/   retry topics, DLT, replay support
services/simulator-service/  chaos endpoints
services/*/                  circuit breakers, health indicators, graceful shutdown
docs/failure-modes.md        the experiments and what actually happened
```

## Design detail

### Retry topics and the dead-letter queue

Blanket in-memory retry is wrong for a partitioned log: retrying a failing message in place
blocks its partition and stalls every other key behind it. The standard shape:

```
main topic → handler throws
  → retriable?   → <topic>.retry-5s   → .retry-1m   → .retry-10m  → <topic>.DLT
  → fatal?       → straight to <topic>.DLT, no retry
```

Classify errors explicitly — this is the design decision, not the plumbing:

| Class | Examples | Action |
|---|---|---|
| **Transient** | DB connection lost, optimistic lock exhausted, downstream 503 | Retry with backoff |
| **Fatal** | Deserialization failure, unknown event type, validation failure, business rejection | DLT immediately |

A deserialization failure that retries is the classic poison-pill outage: the message can
never succeed, the partition never advances, and the consumer group looks alive while
processing nothing. `ErrorHandlingDeserializer` must be configured so this lands in the DLT
on the first attempt.

**Business rejections are not failures.** `INSUFFICIENT_FUNDS` is a legitimate outcome that
emits `EntryRejectedV1` and commits the offset. If it throws and retries, you will retry a
declined payment forever. Getting this boundary right is more important than the retry
machinery itself.

### DLT tooling

A DLT no one looks at is a silent data-loss channel. Build:

- `GET /admin/dlt/{topic}` — browse dead letters with headers (original topic, partition,
  offset, exception, stack trace, attempt count).
- `POST /admin/dlt/{topic}/replay/{offset}` — republish one message to the main topic after
  a fix.
- `POST /admin/dlt/{topic}/replay-all` — drain, with a dry-run mode.
- A Micrometer gauge per DLT so depth is visible in Phase 08's dashboard.

Kafka UI can browse the topic, but a purpose-built view that decodes the exception headers is
what makes this usable, and it is an hour's work.

### Circuit breakers

Resilience4j on every remaining synchronous hop — in practice `payment → fx` (if you kept
the Phase 04 sync design) and the Keycloak Admin API call in `identity-service`.

Configure deliberately rather than by copying defaults: sliding window 20 calls, 50% failure
threshold, 10 s open, 3 calls in half-open. Each breaker needs a defined fallback:

| Breaker | Fallback |
|---|---|
| `payment → fx` | Reject the cross-currency transfer with `FX_UNAVAILABLE`; same-currency transfers keep working |
| `identity → keycloak` | Fail registration fast with a clear message; never leave a half-created user |

The second row is the interesting one: the fallback's job is to preserve the invariant, not
to paper over the outage. Degrading gracefully means *narrowing* what works, not pretending.

### Chaos control plane

`POST /simulator/chaos` with a small, honest set of levers:

| Lever | Effect |
|---|---|
| `ledger.delay=5000` | Ledger consumer sleeps before handling — observe pending states |
| `ledger.fail-rate=0.3` | 30% of handles throw transiently — observe retries |
| `ledger.reject-all` | Every entry rejected — observe compensation |
| `outbox.pause` | Outbox poller stops — observe events stuck in the DB, then drain |
| `fx.down` | `fx-service` returns 503 — observe the circuit breaker |
| `poison <topic>` | Publish an unparseable message — observe DLT, not a stall |
| `duplicate <topic>` | Re-emit the last N messages — observe idempotency holding |

Gate the whole thing behind a `chaos` Spring profile that is off by default. A chaos endpoint
reachable in a demo build is a genuinely bad idea.

### Replay

The payoff of the event log. Two flavours:

1. **Projection rebuild** (safe): drop `analytics-service`'s database, reset its consumer
   group to offset 0, replay. Must produce identical aggregates and zero notifications — the
   replay-mode flag from Phase 06 does the work.
2. **Point-in-time query** (read-only): replay `ledger.entry.v1` up to a timestamp into a
   throwaway schema to answer "what was every balance last Tuesday at 14:00?". This is the
   thing a mutable-balance design simply cannot do, and demonstrating it is the clearest
   justification for the whole event-sourced approach.

`tasks.ps1 analytics-rebuild` and `tasks.ps1 point-in-time <timestamp>` wrap both.

### Scaling consumers

Scale `ledger-service` to 3 replicas:

```powershell
docker compose up -d --scale ledger-service=3
```

Watch in Kafka UI: 12 partitions redistribute 4/4/4. Then kill one instance and watch the
rebalance to 6/6. Things to actually verify, not assume:

- **Ordering per key survives** — all events for one account still land on one instance.
- **No double-processing during rebalance** — the idempotent consumer covers the window where
  a partition moves after a fetch but before a commit.
- **The outbox poller's `SKIP LOCKED`** means three instances publish disjoint rows. Assert no
  duplicate Kafka messages during a 3-replica load run.
- **Cooperative rebalancing** (`CooperativeStickyAssignor`) avoids the stop-the-world pause of
  the default eager protocol. Configure it and note the difference — with eager rebalancing,
  every consumer stops during a scale event; with cooperative, only the moving partitions do.

### WebSocket scaling — the last stateful service

Phase 03 noted that `notification-service` cannot scale horizontally: a user's socket lives on
one instance, but any instance may consume the event for that user. Resolve it here: a Redis
pub/sub channel that every instance subscribes to; the consuming instance publishes to Redis,
and whichever instance holds the socket delivers it. Roughly 50 lines.

**Treat this as a prerequisite rather than an optional cleanup.** Every other service in the
project is stateless and can run N replicas anywhere. This one cannot until the fan-out
exists — not on a laptop, not on ECS, not under Kubernetes. It is the single thing standing
between the system and "deployable at more than one instance per service", which makes it
load-bearing for Phase 09 and for the AWS item in Phase 10.

The proper production answer is an external STOMP relay (RabbitMQ or ActiveMQ). Note that in
`docs/failure-modes.md` rather than building it — the Redis fan-out demonstrates the pattern
and the reasoning, which is the point.

### Graceful shutdown

`server.shutdown=graceful`, a sensible `spring.lifecycle.timeout-per-shutdown-phase`, and
Kafka listener containers stopped before the DB pool closes. Without it, `docker compose
restart` under load produces a scatter of half-processed messages and confusing DLT entries
that look like bugs but are shutdown artefacts.

## Task list

1. Retry-topic chain + DLT in `common-messaging`, with explicit transient/fatal
   classification. Retry and DLT topics get 3 partitions, per the Phase 00 catalogue.
2. `ErrorHandlingDeserializer` so poison messages bypass retry entirely.
3. Audit every consumer: business rejections must commit, not throw.
4. DLT browse/replay admin endpoints + depth gauges.
5. Resilience4j on remaining sync hops, with tested fallbacks.
6. Chaos endpoints behind the `chaos` profile.
7. `analytics-rebuild` and `point-in-time` tooling.
8. `CooperativeStickyAssignor` + graceful shutdown across all services.
9. Redis pub/sub fan-out for `notification-service`.
10. Run the failure experiments; write up `docs/failure-modes.md` with real observations.

## The failure experiments

Run each, record what actually happened, and note anything that surprised you.

| # | Experiment | Expected |
|---|---|---|
| 1 | Kill `ledger-service` mid-transfer | Payment stays PENDING; completes on restart with no user action |
| 2 | Kill `payment-service` after the ledger posts | Money already moved; on restart the result event is consumed and status resolves |
| 3 | Poison `payment.event.v1` | Message in DLT within seconds; partition keeps flowing |
| 4 | `ledger.fail-rate=0.3` under load | Retries drain; zero lost payments; DLT empty |
| 5 | Duplicate the last 100 ledger events | Balances unchanged; `processed_event` absorbs all of them |
| 6 | `outbox.pause` for 60 s, then resume | Events accumulate in the DB, then drain in order; nothing lost |
| 7 | `fx.down` | Breaker opens; cross-currency fails fast; **same-currency transfers unaffected** |
| 8 | Kill Postgres for 30 s | Services report DOWN, recover on reconnect, no corruption |
| 9 | Scale ledger 1→3→1 under load | Clean rebalance; ordering preserved; no duplicate postings |
| 10 | Rebuild the analytics projection | Identical aggregates; zero notifications |
| 11 | Fill a disk / OOM one service | Documented blast radius — which features degrade, which survive |

Experiment 7's second clause is the one worth dwelling on: a well-bounded failure takes down
one capability, not the system. If killing `fx-service` stops same-currency transfers, the
coupling is wrong and Phase 04's variant is the fix.

## Done when

- [ ] All 11 experiments run, with results written up
- [ ] Poison messages reach the DLT without stalling a partition
- [ ] Business rejections never enter the retry chain
- [ ] DLT contents are browsable and replayable through an endpoint
- [ ] Every circuit breaker has a tested fallback that preserves invariants
- [ ] `ledger-service` scales to 3 and back with no duplicates and no ordering violation
- [ ] Analytics rebuilds from offset 0 with identical results and no side effects
- [ ] Point-in-time balance query works for an arbitrary past timestamp
- [ ] `notification-service` delivers correctly with 2+ instances
- [ ] Zero-sum invariant holds after every single experiment

## Risks & gotchas

- **Retry topics multiply partitions.** Three retry levels plus a DLT across ten topics is a
  lot of partitions for one broker. Use 3 partitions for retry/DLT topics, not 12 — ordering
  no longer matters once a message is in the retry chain.
- **Retry chains break ordering by design.** A retried message arrives after later messages
  for the same key. Where ordering is load-bearing (ledger postings), this must not be
  silently accepted — either the operation is commutative/idempotent, or that error class must
  be fatal rather than retriable. Think this through per consumer; it is the subtlest thing
  in the phase.
- **Consumer-group offset reset requires the group to be empty.** Stop the service first, or
  the reset silently no-ops.
- **Chaos in a demo build.** Profile-gate it and add a startup log line when chaos is active,
  so you never spend an hour debugging a fault you injected last week.
- **Experiment 11 is open-ended.** Timebox it — the goal is to know the blast radius, not to
  harden against every resource exhaustion.
