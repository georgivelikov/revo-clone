# 0011. Ports at three seams — and why the broker and dialect are deliberately not abstracted

Date: 2026-10-03

## Context

Three infrastructure layers sit between business logic and a specific vendor: identity (OIDC
provider), messaging (Kafka), and the database (Postgres). It would be possible to add a generic
abstraction over each, so that any of the three could be swapped without touching business code —
but a diagram of interfaces proves nothing about whether a boundary is real, and a lowest-common-
denominator abstraction over all three would have deleted most of what this project exists to
demonstrate (Kafka's partition ordering and replay, Postgres's `SKIP LOCKED` and deferred
constraint triggers are not generic "message broker" or "SQL database" behaviour — they are the
specific mechanisms the outbox pattern and the ledger's invariant depend on).

## Decision

Abstract exactly three seams, no more: `IdentityProviderPort` over user provisioning,
`EventPublisher`/`OutboxAppender` over the broker's publish/consume call sites, and Spring Data
repositories over the database connection. Everything *behind* those seams — partition-key
ordering, consumer groups, offset replay, log compaction for messaging; `SKIP LOCKED`, deferred
constraint triggers, `jsonb`, `ON CONFLICT` for Postgres — is deliberately left unabstracted,
because abstracting it would mean designing to whatever the least capable broker or database in
the swap set can do, which is a worse system for every deployment that could have used the real
feature.

Native SQL is intentionally concentrated in exactly four places across the whole project — the
outbox poller, the balanced-entry trigger, the ledger reconciliation query, and the analytics
aggregate upserts — rather than scattered, so the portability question for the database layer is
a four-item checklist, not a project-wide audit.

## Consequences

**Good.** The identity seam is the one that actually is swappable with no loss, and the project
proves it rather than asserting it: `KeycloakIdentityAdapter` and `InMemoryIdentityAdapter` are
held to behavioral identity by one shared contract test, so "identity is portable" is a fact about
a passing test suite, not a claim about an interface shape. Being explicit that the other two
layers are *not* equally portable is itself valuable information — a future maintainer reading
`docs/architecture.md`'s portability table knows exactly which parts of the system to treat as
load-bearing on Kafka- and Postgres-specific behaviour, instead of discovering it the hard way
during an attempted migration.

**Bad, and this is the status of the claim as of this writing, not a result.** `docs/
architecture.md` calls for three concrete swap experiments to turn "the messaging and database
seams are deliberately narrow" from a design claim into a verified one: running the full suite
against Redpanda in place of Kafka, against a TLS-required Postgres with a non-superuser role,
and confirming both `IdentityProviderPort` adapters pass the same contract test in CI, not just
locally. **These experiments have not been run yet.** Until they are, the honest status of this
ADR's "messaging and database boundaries are real" claim is *designed for*, not *proven* — the
same distinction this project insists on everywhere else (a diagram is not evidence; a passing
test against the real alternative is). Closing that gap is tracked as outstanding phase-09
verification work, not asserted here as already done.
