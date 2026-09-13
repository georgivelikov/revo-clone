# Revolut Clone — Phase Plans (Index)

A learning project: build a Revolut-style neobank to practise distributed-systems
engineering. Regulation and compliance are explicitly out of scope. The value is in the
double-entry ledger, event-driven choreography over Kafka, sagas with compensation,
idempotency under retries, and a product UX that makes eventual consistency visible.

## Locked decisions

| Area | Choice |
|---|---|
| Backend | Java **25 (LTS)**, Spring Boot **4.1.1**, Maven multi-module |
| Spring Cloud | **2025.1.3** ("Oakwood"). 2025.1.2 is the minimum for Boot 4.1 compatibility |
| Topology | True microservices, database-per-service |
| Broker | Apache Kafka, KRaft mode (no ZooKeeper) |
| Auth | Keycloak (OIDC); services are JWT resource servers |
| Frontend | React 19 + TypeScript + Vite, mobile-first, Revolut-styled |
| Infra | `docker compose up` |
| Eventual target | AWS — Aurora/RDS Postgres, MSK, Keycloak on ECS. See §Portability boundaries. |

Verified on this machine: Java 25.0.4 LTS, Maven 3.9.16, Node 22.19, Docker 29.5 /
Compose 5.1.4, 64 GB RAM, 14 logical cores. The full stack idles around 10–12 GB, so running
everything at once is comfortable here.

**Java 25 is the only JDK installed, and that is deliberate.** It is the current LTS,
supported to 2030. Non-LTS releases are patched only until the next release ships — Java 24
died in September 2025 and Java 26 in September 2026 — so tracking the newest JDK means
re-running this decision every six months for a project that takes months to build. Boot 4.1
supports 17 through 26, so pinning 25 gives headroom without costing anything.

The next LTS is **Java 29, September 2027**. There is no reason to revisit this before then.

**Why Boot 4.1 rather than 3.5.** Spring Boot 3.5 reached open-source end-of-life on 30 June
2026, and 3.5.16 was its final OSS patch — the whole 3.x line now receives no further security
fixes outside a commercial Tanzu agreement. Boot 4.1 (June 2026) is current, and is also the
first release supporting Java 26 if the JDK question is ever revisited.

The cost is real and worth knowing up front: Boot 4 brings **Spring Framework 7 and Spring
Security 7**, and most Spring material online still targets Boot 3. Expect to read reference
documentation rather than blog posts more often than you would have a year ago. Phase 00 has
a short list of the specific things to verify.

## Phase index

| # | Phase | Outcome | Rough effort |
|---|---|---|---|
| [00](phase-00-foundations.md) | Foundations | Infra up, shared libraries built and tested | 3–4 d |
| [01](phase-01-identity-account-gateway.md) | Identity, Account, Gateway | Register → token → wallets over HTTP | 3–4 d |
| [02](phase-02-ledger.md) | Ledger | Correct, concurrent, idempotent double-entry core | 5–7 d |
| [03a](phase-03-payments-and-ui.md) | Payment saga | Transfer saga + 3-layer idempotency, verified with curl | 3–4 d |
| [03b](phase-03-payments-and-ui.md) | First UI | React foundation, WebSocket push, two browsers | 3–4 d |
| [04](phase-04-fx-multicurrency.md) | FX & multi-currency | Ticking rates, quotes with expiry, per-currency postings | 4–6 d |
| [05](phase-05-cards.md) | Cards | Auth holds vs settlement, freeze, limits | 4–6 d |
| [06](phase-06-vaults-analytics.md) | Vaults, budgets, analytics | Round-ups, categorisation, spend dashboard | 4–6 d |
| [07](phase-07-resilience-and-scale.md) | Resilience & scale | DLT, chaos, replay, consumer scaling | 4–5 d |
| [08](phase-08-load-and-observability.md) | Load & observability | k6 numbers, metrics, consumer lag | 2–3 d |
| [09](phase-09-polish.md) | Polish | One-command demo, seeded data, README | 2–3 d |
| [10](phase-10-extensions.md) | Extensions | Avro, Debezium, k8s, tracing | open-ended |

Effort figures assume focused solo work and are rough. Phases 0–4 are the core; 5–10 are
additive and can be resequenced freely.

**Read those figures as full-time days, not calendar days.** Phases 00–09 total 37–52 focused
days. At ten hours a week that is four to six months of evenings; at full time it is two
months. Phase 03 is split above because it is really two phases wearing one hat — a saga and
an entire frontend foundation — and treating it as one 6–8 day unit is the most likely place
for the schedule to slip silently. A solo project this size is usually abandoned around
Phase 05, and an unexamined schedule is the usual reason.

Phase 03a is a legitimate stopping point that leaves a working, curl-verifiable system.

## Conventions used throughout

- **Group id** `com.revolutclone`, package root `com.revolutclone.<service>`.
- **Money** is always `long` minor units plus an ISO currency code. Never `double`.
- **Every money-moving POST** takes an `Idempotency-Key` header.
- **Every event** carries `eventId`, `occurredAt`, `correlationId`, `version`.
- **Every Kafka message** is keyed by the entity whose ordering matters (usually account id).
- **Every service** owns its database and never reads another's.
- **Every cross-service write** goes through the transactional outbox — never a direct
  `KafkaTemplate.send()` inside a business transaction.
- **Flyway** owns all schema; no `ddl-auto` beyond `validate`. Migrations run as an ordinary
  owner, never a superuser — managed Postgres does not grant one.
- **No `localhost` in any committed config.** Every endpoint comes from an environment
  variable; local defaults live only in the `local` Spring profile.
- **Logs are JSON on stdout**, with `correlationId` as a field rather than only a pattern.
- **No module is named after a vendor.** `common-messaging`, not `common-kafka`.
- Ports: gateway 8080, identity 8081, account 8082, ledger 8083, payment 8084, fx 8085,
  card 8086, analytics 8087, notification 8088, simulator 8089.
  Infra: Postgres 5432, Redis 6379, Keycloak 8180, Kafka 9092, Kafka UI 8090, web 5173.

## Portability boundaries

Everything runs locally via `docker compose`. The three infrastructure layers sit behind
seams so they can move to managed services later — but **they are not equally swappable**,
and pretending otherwise would mean designing to a lowest common denominator that deletes
most of what this project exists to teach.

| Layer | Abstracted | Not abstracted | Realistic swap |
|---|---|---|---|
| **Identity** | User provisioning (`IdentityProviderPort`), roles-claim path | Nothing — OIDC is a real standard | Any OIDC provider: config plus one adapter class |
| **Messaging** | Publish/consume call sites, via the outbox | Partition-key ordering, consumer groups, offset replay, log compaction, lag-based scaling | MSK, Confluent Cloud, Redpanda. **Not** SQS/SNS/EventBridge/Pub-Sub |
| **Database** | Repository access, connection config | Deferred constraint triggers, `FOR UPDATE SKIP LOCKED`, `jsonb`, `ON CONFLICT` | Aurora/RDS Postgres. **Not** MySQL or DynamoDB |

> **The broker must speak the Kafka protocol.** The design depends on partition-key ordering,
> consumer groups with managed offsets, replay from offset 0, log compaction, and consumer
> lag as a scaling signal. Moving to a queue rather than a log would remove the architectural
> content of Phases 06–08 entirely.

The AWS target is a comfortable fit: MSK is Apache Kafka and Aurora is Postgres, so nothing
in the design has to be surrendered. Keycloak runs on ECS like any other container, so
identity needs no AWS-specific decision either.

Phase 09 verifies these boundaries rather than asserting them — swapping the broker for
Redpanda and running the full suite is about an hour's work and is the only real evidence
the abstraction holds.

## Decisions taken

These were open for a while. Each is settled; the rejected alternative is kept so the
reasoning survives.

1. **Foundations-first, not a walking skeleton.** The goal is distributed-systems practice,
   and the skeleton variant discards the Keycloak integration that Phase 01 budgets half a
   day for. *Rejected:* a throwaway end-to-end slice in Phase 01 for early momentum — the
   motivation problem is better solved by the 03a/03b split above, which puts a working
   system on screen without the rework.
2. **FX stays synchronous in Phase 04, refactored in Phase 07.** `payment → fx` is one
   deliberate sideways HTTP call behind a circuit breaker, because the user must see a firm
   rate before confirming. *Rejected for now:* a compacted rate topic materialised locally —
   it is the better end state, and Phase 07 does it as an explicit exercise. Experiment 7
   only teaches anything if the coupling exists first and you feel it break.
3. **Phases 05–06 keep their architecture, lose product scope.** The auth/settlement split
   and the replayable projection are core, not CRUD. *Cut instead:* merchant logos, the
   6-month trend chart, the top-merchants list, and anything else in the "just one more
   feature" category.

## How to use these files

Each phase file is self-contained and structured the same way:

- **Goal** — one paragraph, what is true when the phase ends
- **Prerequisites** — what must already work
- **Deliverables** — the actual files and modules to create
- **Design detail** — schemas, contracts, algorithms worth getting right
- **Task list** — ordered, checkable steps
- **Tests** — named tests that must exist and pass
- **Verification** — commands to run and what you should see
- **Done when** — the exit criteria
- **Risks & gotchas** — the things that will actually bite

Work a phase top to bottom. Do not start the next phase until the "Done when" list is
fully green — these phases compound, and a wrong ledger in Phase 02 poisons everything
after it.
