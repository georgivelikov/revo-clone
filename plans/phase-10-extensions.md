# Phase 10 — Extensions

**Effort:** open-ended, pick independently · **Prerequisites:** [Phase 09](phase-09-polish.md) · [← Index](00-overview.md)

## Goal

The project is complete and demonstrable after Phase 09. Everything here is optional and
independent — pick what you want to learn next. Each item below is sized, scored for what it
actually teaches, and honest about whether it is worth the time.

Ordered by learning-value-per-day, best first.

---

## A. Schema Registry and Avro

**~3 days · high value**

Replace JSON contracts with Avro schemas in Confluent Schema Registry, with `BACKWARD`
compatibility enforced at the broker.

The lesson is not the serialisation format — it is that **schema evolution is a distributed
systems problem**. With JSON, a producer can add a required field and break every consumer
silently at runtime. With a registry, that deploy is rejected at publish time. Doing this
after living with JSON for nine phases means you have felt the problem before applying the
fix.

Build: registry container, `.avsc` schemas in `platform/contracts`, the Avro Maven plugin
generating records, `KafkaAvroSerializer`, and a CI check that fails if a schema change is
incompatible. Then deliberately try to make a breaking change and watch it get rejected.

Real cost: generated classes are uglier than records, local dev gains a dependency, and
debugging with `kafka-console-consumer` needs the Avro-aware variant.

---

## B. Debezium CDC outbox

**~2 days · high value**

Replace the polling outbox publisher with Debezium reading the Postgres WAL.

Worth doing because it directly contrasts two solutions to the same problem you have already
solved once. The poller costs a query every 500 ms per service and caps end-to-end latency at
the poll interval; CDC is near-zero-latency and zero-load, at the cost of a Kafka Connect
cluster, replication-slot management, and a much worse failure mode when a slot fills a disk.

Build: Connect container, Debezium Postgres connector with the outbox event router SMT,
`wal_level=logical`, then measure the end-to-end latency difference against Phase 08's
baseline. That measured delta is the deliverable.

The honest conclusion may well be "the poller was fine for this scale" — which is a genuinely
valuable thing to have established with numbers.

---

## C. Distributed tracing

**~1–2 days · high value, low cost**

OpenTelemetry with Tempo or Jaeger, trace context propagated through Kafka headers.

If Phase 08 was skipped, do it here. One transfer becomes a single waterfall spanning gateway
→ payment → Kafka → ledger → Kafka → notification, with the queue wait visible as a gap. That
picture explains asynchronous latency better than any amount of reasoning about it, and it
makes "where did the 200 ms go" a five-second question.

Cheapest high-value item on this list. Micrometer Tracing does most of the work; propagating
context across Kafka headers is the only fiddly part.

---

## C2. Deploy to AWS

**~3–4 days · high value if you want to see it run somewhere real**

Aurora Postgres, MSK, Keycloak on ECS, the services on ECS Fargate, secrets in SSM, images in
ECR.

This is a deployment exercise rather than a rewrite, and only because of the groundwork:
Phase 00's config externalisation, Phase 09's probes and image discipline, and Phase 07's
Redis fan-out that made every service stateless. **Do not start until Phase 09's four
portability checks pass** — they exist precisely to find the problems on your laptop rather
than in a VPC.

The target was chosen to demand no design changes: MSK is Apache Kafka, so partition
ordering, compaction, offset replay and lag-based scaling all survive intact; Aurora is
Postgres, so the deferred constraint trigger and `SKIP LOCKED` work as written.

Two things that will actually cost time:

- **MSK uses IAM SASL authentication.** That is a client-library dependency and a config
  block, not code — but it is the one place the messaging adapter is not a straight lift.
- **Verify MSK Serverless against the Phase 00 topic catalogue** before choosing it over a
  provisioned cluster. Check compaction support and whether infinite retention is available;
  `ledger.entry.v1` and `card.authorization.v1` depend on both, and Phase 06's replay
  silently produces partial results if retention quietly truncates.

Budget most of the time for networking and IAM rather than for anything interesting. The
deliverable worth having is a written comparison of what changed between laptop and cloud —
ideally very little, which is the whole point of the exercise.

---

## D. Kubernetes with autoscaling

**~3–4 days · medium value**

kind cluster, Helm charts or plain manifests, HPA on `ledger-service` driven by Kafka
consumer lag via KEDA.

The interesting part is specifically KEDA scaling on lag rather than CPU — CPU is a poor
proxy for "this consumer group is behind". Watching replicas scale 1 → 6 during the Phase-08
spike test and back down is a satisfying demonstration of the whole architecture's point.

The unglamorous 80% is YAML, secrets, service accounts, ingress and networking. Worth it if
k8s is something you want on your CV; skip it if the interest is really in the distributed
systems rather than the orchestration.

---

## E. Scheduled and recurring payments

**~2 days · medium value**

Standing orders and scheduled transfers.

Teaches distributed scheduling, which is harder than it looks: with N replicas, exactly one
must fire each due payment. Options are a DB-backed scheduler with `SKIP LOCKED` (consistent
with the outbox pattern already in use), ShedLock, or Quartz clustered mode. Also forces you
to confront timezones, month-end semantics ("the 31st" in February), and retry policy for a
payment that fails at 3 a.m.

Good product feature and a genuinely different concurrency problem from everything so far.

---

## F. Direct debits and a clearing simulator

**~3 days · medium value**

An external bank simulator with SEPA-like semantics: batch files, T+1 settlement, returns and
reversals days later, and a mandate lifecycle.

The lesson is integration with a system that is slow, batch-oriented and can reject you a week
after the fact — which forces long-lived sagas with timeouts measured in days, and states that
cannot be resolved by retrying. Much closer to real banking than anything built so far.

---

## G. Kafka Streams for analytics

**~2–3 days · medium value**

Rewrite `analytics-service`'s aggregation as a Kafka Streams topology with windowed
aggregations and a state store, exposed via interactive queries.

Direct comparison against the hand-rolled consumer-plus-Postgres version from Phase 06: less
code, built-in windowing and exactly-once semantics, but state stores, changelog topics and
rebalancing become your problem, and debugging a topology is harder than debugging a loop.

Do it if stream processing is interesting to you. It also slots in as the natural home for
Phase 04's compacted-rate-table variant.

---

## H. Statements and documents

**~1–2 days · low value, high polish**

Monthly PDF statements generated from the ledger, downloadable and emailed.

Teaches almost nothing new architecturally — it is a consumer, a template and a PDF library.
But statements generated directly from immutable postings are the visible payoff of
double-entry, and a PDF is a tangible artefact. Cheap, and it makes the demo feel finished.

---

## I. Security hardening

**~2–3 days · low value here, high value generally**

Rate limiting per endpoint, request signing, field-level encryption, audit log, secret
management with Vault, dependency scanning, `docker scout` in CI.

Outside the project's stated focus, but the audit log in particular is a natural fit: an
append-only record of every state change is something you already have in the event log, and
exposing it as a queryable audit trail is a couple of hours.

---

## J. Mobile app

**~1–2 weeks · low value for the stated goal**

React Native / Expo client against the same API.

Teaches mobile, not distributed systems. Worth it only if mobile is a goal in itself. A
monorepo sharing design tokens and the API client with the web app is the sane structure if
you do.

---

## Recommended combinations

| If you want to… | Do |
|---|---|
| Deepen the event-driven story | A + B + G |
| Get better at operating systems | C + C2 + D |
| See it run somewhere real | C2, then D |
| Make it more realistic as a bank | E + F + H |
| Make it a stronger portfolio piece | C + H + more Phase 09 polish |
| Learn the most per day spent | C, then A, then B |

C2 and D overlap: if you are going to do D (EKS with KEDA scaling on consumer lag), do C2
first with ECS. Getting the networking, secrets and images right once on the simpler runtime
makes the Kubernetes version mostly a manifest exercise.

## A note on stopping

Phase 09 is a complete project. Everything here is a bonus, and a finished nine-phase project
is worth considerably more than an abandoned thirteen-phase one. If interest is fading,
stopping at Phase 09 with good documentation is the right call, not a compromise.

The one item worth doing almost regardless is **C (tracing)** — a day and a half, and it makes
everything built in the previous nine phases legible in a single picture.
