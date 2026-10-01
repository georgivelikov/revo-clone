# Revolut clone — project constitution

A learning project: a Revolut-style neobank built to practise distributed-systems
engineering. Regulation and compliance are out of scope. The value is in the double-entry
ledger, event-driven choreography over Kafka, sagas with compensation, idempotency under
retries, and a UX that makes eventual consistency visible.

`plans/` is the specification and is read-only. Work the current task from `agent/state.json`;
do not range ahead into later phases.

## Stack — pinned, do not change

| Area | Pin |
|---|---|
| Java | **25 (LTS)** — the only JDK installed, deliberately |
| Spring Boot | **4.1.1** |
| Spring Cloud | **2025.1.3** (2025.1.2 is the minimum for Boot 4.1) |
| Build | Maven multi-module, group `com.revolutclone`, packages `com.revolutclone.<service>` |
| Broker | Apache Kafka, KRaft mode (no ZooKeeper) |
| Auth | Keycloak (OIDC); services are JWT resource servers |
| Frontend | React 19 + TypeScript + Vite, mobile-first |
| Infra | `docker compose` |

## Boot 4 rule — read this before writing Spring code

Boot 4.1 brings **Spring Framework 7 and Spring Security 7**. Most Spring material online
still targets Boot 3, and Boot 4 shipped after the model's training cutoff.

- **`docs/spring-boot-4-notes.md` is the authority.** Check it first. It records verified
  facts with the `docs.spring.io` URL each came from.
- When an online answer disagrees with the reference documentation, **the documentation is
  right and the answer is about Boot 3.**
- If a fact you need is not in the notes file, fetch it from `docs.spring.io`, use it, and
  append it to the notes file with its source URL. Never guess an API shape.
- Prefer `start.spring.io` coordinates over coordinates copied from examples.

## Hard rules

These are invariants, not preferences. Violating one is a build failure.

1. **Money is `long` minor units plus an ISO currency code. Never `double`, never `float`.**
   `BigDecimal` is permitted only inside `FxMath` and `toMajor` — nowhere else.
2. **Never weaken a test to make a build pass.** Deleting a test, `@Disabled`, `@Ignore`,
   `assumeTrue`, `assumeThat`, or loosening an assertion to match wrong behaviour is a build
   failure. If a test is genuinely wrong, fix the test and say so in the commit body. The
   total test count must never drop.
3. **Every money-moving POST takes an `Idempotency-Key` header**, and honours it.
4. **Every event carries `eventId`, `occurredAt`, `correlationId`, `version`.**
5. **Every Kafka message is keyed by the entity whose ordering matters** — usually account id.
6. **Every cross-service write goes through the transactional outbox.** A
   `KafkaTemplate.send()` inside a business transaction is forbidden outside
   `common-messaging/spring/`.
7. **Every service owns its database and never reads another's.**
8. **Flyway owns all schema.** `ddl-auto` never exceeds `validate`. Migrations run as an
   ordinary owner role, never a superuser — managed Postgres does not grant one.
9. **No `localhost` in committed config.** Every endpoint comes from an environment variable.
   Local defaults live in a **separate `application-local.yml`**, never as a `local` profile
   document inside `application.yml` — the tripwire checks this by filename, so a `local`
   document in the main file reads as a violation and fails the gate.
10. **Logs are JSON on stdout**, with `correlationId` as a field, not merely in a pattern.
11. **No module is named after a vendor.** `common-messaging`, not `common-kafka`.
12. **Never edit `plans/**`, `.claude/**`, `agent/gate.ps1` or `agent/tripwire.ps1`.** These
    are the specification and the gate that measures you. They are permission-denied; treat an
    urge to change them as a sign you have misread the task.

## Ledger invariant

Every journal entry sums to zero across its postings. This is enforced three ways — a deferred
constraint trigger, a test, and a continuous gate check:

```sql
SELECT transaction_id FROM ledger_entry
GROUP BY transaction_id HAVING SUM(amount_minor) <> 0;
```

Any row returned is a hard failure regardless of what the test suite says. The sign convention
and balance-column semantics are defined in `plans/phase-02-ledger.md` — follow that table
exactly, and reproduce it as a comment at the top of `PostingEngine`.

## Port map

Gateway 8080, identity 8081, account 8082, ledger 8083, payment 8084, fx 8085, card 8086,
analytics 8087, notification 8088, simulator 8089.
Infra: Postgres 5432, Redis 6379, Keycloak 8180, Kafka 9092, Kafka UI 8090, web 5173.
Observability (`docker-compose.obs.yml`, phase 08): Prometheus 9090, Grafana 3000,
kafka-exporter 9308, postgres-exporter 9187.

## Working style

- **Tests first** where the phase says so — `common-money` and the ledger especially.
- `mvn -q clean verify` from the repo root must be green before you finish. The `Stop` hook
  runs `agent/gate.ps1` and will not let you end your turn on a red build. Fix the build; do
  not argue with the gate.
- Small, focused commits. The commit body explains *why*, and names any test you changed.
- Shell is PowerShell 7 (`pwsh`) on Windows. Git Bash is available for POSIX scripts. `.sh`
  files committed for Linux containers need LF endings.
