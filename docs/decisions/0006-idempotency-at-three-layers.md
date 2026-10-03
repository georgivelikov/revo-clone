# 0006. Idempotency at three layers

Date: 2026-10-03

## Context

A money-moving request can be retried for reasons entirely outside the caller's control: a
mobile client that times out and resubmits, a load balancer that redelivers, a Kafka consumer
that reprocesses a message after a crash before committing its offset. Any one of these, left
unhandled, can move money twice for what the user experiences as a single transfer. No single
layer of the stack sees every one of these retry paths.

## Decision

Three independent, differently-scoped idempotency mechanisms, deliberately redundant with each
other:

1. **Edge (Redis).** The gateway's idempotency filter keys on `(jwt.sub, Idempotency-Key)` with
   a 24-hour TTL and a request-body hash, so a byte-identical retry replays the original
   response and a same-key-different-body retry is rejected with 422. Fast, but Redis is an
   optimization and may be flushed at any time.
2. **Service (a unique database constraint).** `unique (initiator_user_id, idempotency_key)` on
   the `payment` table is what actually guarantees correctness — a caught constraint violation
   is turned back into the existing row, not treated as an error. This is the layer that holds
   even if Redis has no memory of the request at all.
3. **Consumer (`processed_event`).** Every Kafka consumer records `(consumer_group, event_id)`
   in the same transaction as its side effect; a duplicate key means "already done," ack and
   move on. This is the only layer of the three that sees a *redelivered Kafka message* rather
   than a *retried HTTP request* — the first two never observe this failure mode at all.

## Consequences

**Good.** Each layer catches a retry the others structurally cannot: the edge layer never sees a
Kafka redelivery, and the consumer layer never sees an HTTP retry that never made it past the
gateway. A payment is the one place in the system where "probably fine" is not an acceptable
answer, and three independent mechanisms failing simultaneously is a far smaller risk surface
than any one of them failing alone.

**Bad.** Three places to get idempotency right is three places to get it *wrong*, and they are
not interchangeable: the ledger key derived for a payment is deliberately **not** the client's
own `Idempotency-Key` header (it's a server-derived `payment:<paymentId>`), specifically because
`journal_entry.idempotency_key` is globally unique while the client's header is only unique per
initiator — forwarding it verbatim would let two different users who happened to choose the same
key collide in the ledger. Every new money-moving endpoint has to re-derive this reasoning
correctly rather than copy a pattern blindly. It is also simply more code and more test surface
than a single mechanism would be — `TransferHappyPathTest`'s own suite dedicates multiple cases
purely to proving the three layers don't contradict each other.
