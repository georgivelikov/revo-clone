# Phase 00 — Foundations: Report

Phase 00 is complete. All 15 tasks (00.0–00.14) landed on `master`, each independently
verified against `agent/gate.ps1` (full reactor build + tripwire scan + named tests + a
test-count-never-drops baseline), then merged and tagged. Final state: 125 tests passing,
`mvn clean verify` green, live infrastructure verification passed at the end.

## What existed before this session

Tasks 00.0–00.3 were already done: JDK 25 toolchain confirmed, git hygiene
(`.gitignore`/`.editorconfig`/`.gitattributes`), the root `pom.xml` (Boot 4.1.1 parent,
Java 25, Spring Cloud 2025.1.3 BOM), and the `platform`/`services` Maven aggregators.
Task 00.4 was mid-flight and broken — its five test classes existed but the classes they
tested (`Money`, `FxMath`) didn't, so the module didn't compile.

## What this session built, task by task

**00.4 — `common-money`.** Wrote `Money` (a `long`-minor-units + `Currency` record:
`of`/`ofMajor`/`zero`, arithmetic, `allocate` via largest-remainder, `toMajor`) and
`FxMath.convert` (the one sanctioned `BigDecimal` use, `HALF_EVEN` rounding). Got the five
pre-existing tests green without touching them.

**00.5 — `contracts`.** `EventEnvelope<T>` + `DomainEvent` marker interface, plus 12 event
stubs (`UserRegisteredV1`, `AccountOpenedV1`, `PaymentInitiatedV1`, etc.) with fields sourced
from grepping the actual phase 01–03 specs rather than invented. Jackson 3 (`tools.jackson`)
round-trip test.

**00.6 — `common-messaging`.** The largest task: `api/`+`spring/` port/adapter split, the
transactional outbox (`OutboxEvent` entity, `FOR UPDATE SKIP LOCKED` poller, proven safe under
concurrent publishers), an idempotent-consumer wrapper (`ProcessedEvent`, duplicate-key =
no-op), Kafka error handling (3 retries → DLT, poison messages skip straight to DLT), Jackson 3
`JsonSerde`. Along the way it found and fixed a real environment bug: this machine's JVM
default timezone broke every Testcontainers Postgres connection.

**00.7 — `common-web`.** RFC-7807 `ProblemDetail` error responses, a correlation-ID filter
(mint-or-reuse, MDC + response header), structured JSON logging via Boot 4.1's built-in
support, springdoc OpenAPI.

**00.8 — `common-security`.** JWT resource-server config, `ConfigurableRolesClaimConverter`
(reads Keycloak's nested `realm_access.roles` from a configurable path), `IdentityProviderPort`
with two implementations — `KeycloakIdentityAdapter` (real admin client) and
`InMemoryIdentityAdapter` — proven behaviorally identical by one shared contract test run
against both, the Keycloak side backed by a real Testcontainer.

**00.9 — Postgres init.** `infra/postgres/init-databases.sql`: 8 databases (identity, account,
ledger, payment, fx, card, analytics, notification), each owned by its own non-superuser role —
matching the no-superuser constraint managed Postgres imposes for real. (This task was
interrupted partway through by a stop; the file it had already written was correct, so the
retry just verified and landed it rather than redoing it.)

**00.10 — `docker-compose.infra.yml` + Kafka topics.** Postgres 17, single-node KRaft Kafka,
Kafka UI, Redis, Keycloak. Brought the stack up for real and verified all 8 catalogue topics
exist with 12 partitions and correct cleanup/retention (`ledger.entry.v1` and
`card.authorization.v1` at infinite retention, as specified). Caught and fixed several live
bring-up bugs: a missing `POSTGRES_PASSWORD`, a Keycloak volume-mount permission error,
deprecated Keycloak admin env vars, and a bare `quay.io/keycloak/keycloak:26` tag that no
longer exists on the registry (pinned to `26.7` instead).

**00.11 — Keycloak realm export.** `infra/keycloak/revolut-realm.json`: realm `revolut`, public
PKCE client `revolut-web`, confidential client `revolut-backend` with admin-API rights, roles
`customer`/`admin`, seed users `alice@demo.io`/`bob@demo.io`. Verified live — reset the
Keycloak volume, confirmed a fresh import, pulled a real JWT for both seed users, and confirmed
the backend client's admin-API grant actually works (not just present in the JSON).

**00.12 — `tasks.ps1`.** `up`/`down`/`reset`/`logs <svc>`/`psql <db>`/`topics [--describe]`.
Every subcommand exercised live. Found and fixed a line-ending corruption bug in how the topic
script was piped into the Kafka container.

**00.13 — Config hygiene.** Audited every module's config for hardcoded `localhost`/non-env-var
endpoints. Found no violations (the Spring modules don't ship their own `application.yml` yet
— that's a service concern starting Phase 01), but added a standing `ConfigHygieneTest` that
scans the repo at build time so future services can't quietly regress this rule.

**00.14 — README + full phase verification.** Wrote the root `README.md` (what the project is,
how to run it, the port table). This is the phase's last task, so it also triggered the
harness's full phase-verification: config-hygiene grep, container health, and the topic
catalogue, all checked against a live stack. All passed.

## Notable friction along the way

- **Sub-agent misfires.** Several of the forks dispatched for individual tasks returned
  instantly with zero actual tool calls — essentially narrating instead of working. Each was
  caught by checking the actual repo/git state rather than trusting the report, and retried
  with a more forceful instruction until real work happened. Nothing was marked done without
  independent verification.
- **A harness gap.** `agent/phase-verify.ps1` (read-only, can't be edited) calls bare
  `docker compose ps`, which fails to resolve because this project's compose file is
  deliberately named `docker-compose.infra.yml`, not the Docker-default name. Routed around it
  with a session-scoped `COMPOSE_FILE` environment variable rather than touching the protected
  script.

## Final state

`agent/state.json` shows all 15 phase-00 tasks `done` with recorded commit SHAs, and `current`
advanced to `01.1` — the first task of Phase 01 (identity/account/gateway) — which has not been
started.
