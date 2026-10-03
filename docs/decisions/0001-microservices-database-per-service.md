# 0001. Microservices with database-per-service over a modular monolith

Date: 2026-10-03

## Context

The point of this project is to practise distributed-systems engineering: event-driven
choreography over a real broker, sagas with compensation, idempotency under genuine
redelivery, and eventual consistency a user can actually observe. A modular monolith — one
JVM, one database, well-factored packages — can model some of this in miniature, but it
cannot produce a real network partition, a real partial failure, or a real "two services
disagree because an event hasn't arrived yet." Those conditions are the entire subject matter.

## Decision

Ten independently deployable Spring Boot services (`api-gateway`, `identity`, `account`,
`ledger`, `payment`, `fx`, `card`, `analytics`, `notification`, `simulator`), each with its own
Postgres database and its own non-superuser owner role. No service reads another's tables.
State crosses a service boundary only by publishing an event (through the transactional
outbox — [ADR 0003](0003-transactional-outbox.md)) or, in a documented handful of cases, a
synchronous HTTP call to a specific, narrow endpoint.

## Consequences

**Good.** Every "distributed systems" lesson the project exists to teach is authentic rather
than simulated: Kafka really can redeliver a message, a service really can be down while
another keeps running, an event really can arrive out of order if it's keyed wrong. Each
service is independently restartable, scalable, and — as phase 08 proved — independently
root-causeable under load (the outbox poller's throughput ceiling was found by watching one
service's metrics, not by reasoning about the whole system at once). The database-per-service
rule also forces every cross-service read to become an explicit, visible event or call, which
makes the system's actual coupling legible in a way a shared schema never would.

**Bad.** Ten JVMs, eight Postgres databases, a Kafka cluster, Redis, and Keycloak is a large
amount of infrastructure for a system with no real users — `docker compose up -d` takes over
two minutes even with every readiness check tuned (`docs/performance.md`'s cold-start
measurement), and local development requires Docker Desktop running at all times. Debugging a
single user-facing action means correlating logs across three or four services by
`correlationId`, not reading one stack trace. A modular monolith would have taken a fraction of
the calendar time to build and would still satisfy every *business* requirement this project
has — the extra cost here is paid entirely for the learning value, and would not be justified
by the product on its own merits.
