# 0004. Partition by account id

Date: 2026-10-03

## Context

Kafka only guarantees ordering within a single partition, never across an entire topic. Several
events in this system must never be processed out of order relative to each other — most
obviously, two postings against the same ledger account, or two authorizations against the same
card. The question is which field to use as the message key, since the key is what determines
partition assignment.

## Decision

Every topic is keyed by the entity whose ordering actually matters for that event — in practice
almost always the account id (`ledger.entry.v1`, `account.account.v1`), sometimes the card id
(`card.authorization.v1`), and deliberately *not* the id of the thing that caused the event
(a payment id, a quote id) when that id isn't what needs ordering. `payment.event.v1` is the one
documented exception, keyed by `paymentId` — because the ordering that matters there is "every
event about one payment, in order," not "every event about one account," and a single ledger
account can be the target of many unrelated, concurrently-settling payments that don't need to
serialize against each other.

## Consequences

**Good.** The ordering guarantee is the broker's, for free — no application-level sequencing,
no "wait for the previous event" logic. Phase 07's cooperative-sticky rebalancing and phase 08's
scale-out testing both depend on this: a consumer group of 1, 2, 3, 4, 6, or 12 instances (12 was
chosen as the partition count specifically because it's highly composite) can all divide the
keyspace evenly and every instance only ever sees a self-consistent slice of accounts.

**Bad.** A disproportionately active account becomes a hot partition, and phase 08's hotspot
experiment measured exactly what that costs: zero optimistic-lock retries at single-instance
scale (the mechanism never even activates with one sequential consumer), rising to a 6.7% retry
rate once the system was genuinely scaled to three `ledger-service` instances actually
contending for the same account — handled correctly (100% success, `PostingEngine`'s retry
absorbing every conflict) but visible in tail latency (p95 22ms → 83ms, max up to 959ms). The
partition count is also fixed at topic creation; repartitioning later to add capacity would
change which partition a given key lands on and break the ordering guarantee during the
transition, which is why the catalogue was sized up front rather than grown incrementally.
