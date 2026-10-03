# 0008. JSON over Avro/Schema Registry

Date: 2026-10-03

## Context

Every event on Kafka needs a wire format. Avro with a Schema Registry is the conventional
"serious" choice for an event-driven system at this scale — enforced schemas, compatibility
checking on deploy, compact binary encoding. Plain JSON is the simpler choice, with none of
that enforcement.

## Decision

Plain JSON, via Jackson 3 (`tools.jackson`), for every event payload. No Schema Registry, no
Avro, no binary format. `EventEnvelope<T>` wraps every payload with `eventId`, `occurredAt`,
`correlationId`, and `version` — a hand-maintained convention, not a tool-enforced one.

## Consequences

The honest answer is that **JSON was simpler and the tradeoff is no enforced schema evolution.**
This is not a decision made for a principled technical reason that happens to also be convenient
— it was the pragmatic choice for a project whose event contracts were still changing shape
across almost every phase, and standing up a Schema Registry and an Avro build pipeline before
the contracts had stabilized would have added friction to exactly the part of the project that
needed to move fastest.

**Good.** Zero extra infrastructure to run or operate. Every message is human-readable directly
in Kafka UI, which made debugging the saga flows during development meaningfully faster — seeing
`PaymentInitiatedV1`'s actual JSON body while chasing a bug is strictly easier than decoding Avro
by hand. Changing a contract was a one-service, one-commit change with no registry coordination
step.

**Bad, and this is the real cost, not a hedge.** There is no compile-time or deploy-time check
that a producer and a consumer agree on a schema. A field renamed on the producer side breaks
every consumer silently — not with a build failure, but with a `null` the consumer may not even
notice until it produces a wrong downstream result. There is no automatic compatibility gate
before a deploy ships a breaking change. `EventEnvelope`'s `version` field is the only defense
this project has against that, and it is a human convention enforced by code review, not a tool.
For a system with external, independently-deployed consumers, this would be a genuine production
liability, not a stylistic preference — Avro with a registry is almost certainly the right call
once more than one team owns the producers and consumers of a topic.
