# Revolut Clone

A Revolut-style neobank built to practise distributed-systems engineering. Regulation and
compliance are out of scope; the value is in the double-entry ledger, event-driven
choreography over Kafka, sagas with compensation, idempotency under retries, and a UX that
makes eventual consistency visible.

The full design — system context, event/topic flow, the transfer saga, the ledger data model
and its sign convention, cross-cutting patterns — lives in [`docs/architecture.md`](docs/architecture.md).
The build itself is driven phase by phase from [`plans/`](plans/00-overview.md), starting with
[`plans/phase-00-foundations.md`](plans/phase-00-foundations.md).

## Current state

**Phase 00 — Foundations — is complete.** Five shared libraries and a local infra stack exist;
no business service exists yet. That starts in Phase 01 (identity, account, gateway).

### What's built

| Module | What it is |
|---|---|
| `platform/common-money` | `Money` (long minor units + ISO currency), `FxMath` — the one place a rate is applied. No `double`/`float` anywhere in the project. |
| `platform/contracts` | `EventEnvelope<T>`, the `DomainEvent` marker interface, and stub records for the events Phases 01–03 will publish. Plain Java, no Spring — the one compile-time dependency every service shares. |
| `platform/common-messaging` | The transactional outbox (`OutboxEvent`/`OutboxAppender`/`OutboxPublisher`, `SKIP LOCKED` polling), the idempotent-consumer pattern (`ProcessedEvent`/`IdempotentHandler`), Kafka error handling with a DLT, and a Jackson-3-based `JsonSerde` — split into a broker-free `api/` and a Kafka-specific `spring/` adapter. |
| `platform/common-web` | RFC-7807 `ProblemDetail` error responses, `CorrelationIdFilter`, structured JSON logging with `correlationId` as a field, springdoc OpenAPI wiring. |
| `platform/common-security` | JWT resource-server config, `ConfigurableRolesClaimConverter` (reads the roles claim path from config rather than hardcoding Keycloak's `realm_access.roles`), `IdentityProviderPort` with a `KeycloakIdentityAdapter` and a broker-free `InMemoryIdentityAdapter` proven identical by a shared contract test. |
| `docker-compose.infra.yml` + `infra/` | Postgres 17 (8 databases, one non-superuser role per service), Kafka (KRaft, `auto.create.topics.enable=false`, the 8-topic catalogue with per-topic partitions/cleanup/retention), Kafka UI, Redis, Keycloak 26 (realm `revolut`, pre-imported). |
| `tasks.ps1` | The one wrapper script for the infra stack — see below. |

### What's next

Phase 01 onward builds the services themselves: `api-gateway` and `identity-service` and
`account-service` first, then the ledger (Phase 02, the highest-stakes phase in the project),
payments and the web UI (Phase 03), FX (Phase 04), cards (Phase 05), analytics and vaults
(Phase 06), then resilience, load testing, and polish. See `plans/` for the full roadmap —
it is the actual source of truth, this README will not try to restate it.

## How to run

### Prerequisites

- **JDK 25**, with `JAVA_HOME` pointing at it. Maven resolves the JDK via `PATH` regardless,
  but IDE run configurations and some plugins read `JAVA_HOME` directly.
- **Docker Desktop**, running, with the `docker compose` CLI plugin (v2).
- **PowerShell 7** (`pwsh`) — `tasks.ps1` is PowerShell; Git Bash is fine for everything else.

### Build

```powershell
mvn clean verify
```

Builds every module in the reactor (`platform/common-money`, `platform/contracts`,
`platform/common-messaging`, `platform/common-web`, `platform/common-security`) and runs their
tests. `common-messaging` and `common-security` include Testcontainers-backed integration
tests (Postgres, and a real Keycloak container for the identity-adapter contract test), so
Docker must be running for `mvn clean verify` to pass, not just for the infra stack below.

### Infra stack

```powershell
.\tasks.ps1 up                # bring up postgres, kafka, kafka-ui, redis, keycloak; wait healthy
.\tasks.ps1 topics             # create the Kafka topic catalogue (idempotent)
.\tasks.ps1 topics --describe  # show cleanup.policy / retention.ms for every declared topic
.\tasks.ps1 logs <svc>         # tail a service's logs (postgres|kafka|kafka-ui|redis|keycloak)
.\tasks.ps1 psql <db>          # open psql as that database's own owner role
.\tasks.ps1 down               # stop the stack, keep volumes
.\tasks.ps1 reset              # stop the stack AND drop its volumes
```

`reset`, not `down`, is what you want after editing `infra/keycloak/revolut-realm.json` —
Keycloak's `--import-realm` only imports against an empty database, so a realm edit needs a
volume drop to actually take effect. `psql <db>` accepts one of `identity`, `account`,
`ledger`, `payment`, `fx`, `card`, `analytics`, `notification` — each is both the database
name and the role that owns it.

### Once the stack is up

- **Kafka UI** — <http://localhost:8090>. Shows the topic list and consumer groups.
- **Keycloak admin console** — <http://localhost:8180>, `admin` / `admin`. Realm `revolut`.
- **A token for a seed user**, against the pre-imported `revolut` realm (`alice@demo.io` /
  `bob@demo.io`, password `demo`, both hold the `customer` realm role):

  ```bash
  curl -s -X POST http://localhost:8180/realms/revolut/protocol/openid-connect/token \
    -d grant_type=password \
    -d client_id=revolut-web \
    -d username=alice@demo.io \
    -d password=demo
  ```

  Decode the returned `access_token` and check `realm_access.roles` contains `customer`.

## Port map

| Service | Port |
|---|---|
| `api-gateway` | 8080 |
| `identity-service` | 8081 |
| `account-service` | 8082 |
| `ledger-service` | 8083 |
| `payment-service` | 8084 |
| `fx-service` | 8085 |
| `card-service` | 8086 |
| `analytics-service` | 8087 |
| `notification-service` | 8088 |
| `simulator-service` | 8089 |
| Postgres | 5432 |
| Redis | 6379 |
| Keycloak | 8180 |
| Kafka | 9092 |
| Kafka UI | 8090 |
| web (React/Vite) | 5173 |

Every port above 8089 is a service that doesn't exist yet — this table is copied straight
from `CLAUDE.md`'s pinned port map, not from anything currently running.

## Stack

Java 25 · Spring Boot 4.1.1 · Spring Cloud 2025.1.3 · Maven multi-module · Apache Kafka
(KRaft) · Keycloak (OIDC) · React 19 + TypeScript + Vite · `docker compose`.

Spring Boot 4.1 ships Spring Framework 7 and Spring Security 7 and shipped after most models'
training data — `docs/spring-boot-4-notes.md` is the running, sourced record of what's actually
true about this stack (Jackson 3's package move to `tools.jackson`, Testcontainers 2.x's
module-prefixed coordinates, and so on) versus what's true only for Boot 3.
