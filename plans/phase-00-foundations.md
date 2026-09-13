# Phase 00 — Foundations

**Effort:** 3–4 days · **Prerequisites:** none · [← Index](00-overview.md)

## Goal

A single `docker compose -f docker-compose.infra.yml up -d` brings up Kafka, Postgres,
Redis, Keycloak and Kafka UI. The Maven reactor builds cleanly from the repo root and
produces five shared libraries — money, contracts, messaging, web, security — each with
tests. No business service exists yet. This phase is entirely about making the next eight
cheap.

## Deliverables

```
Revolut/
├── .gitignore
├── .editorconfig
├── README.md
├── pom.xml                            # parent + aggregator
├── tasks.ps1                          # up / down / logs / psql / topics / seed
├── docker-compose.infra.yml
├── infra/
│   ├── postgres/init-databases.sql
│   ├── kafka/create-topics.sh
│   └── keycloak/revolut-realm.json
├── platform/
│   ├── pom.xml
│   ├── common-money/
│   ├── contracts/
│   ├── common-messaging/              # api/ + spring/ — never named after the broker
│   ├── common-web/
│   └── common-security/               # api/ + keycloak/
└── services/pom.xml                   # empty aggregator, filled from Phase 01
```

## Design detail

### Maven layout

Root `pom.xml` is both the parent (inherits `spring-boot-starter-parent` 4.1.1) and the
aggregator. It declares `<java.version>25</java.version>`, imports
`spring-cloud-dependencies:2025.1.3` in `dependencyManagement`, and pins the project's own
modules so services can depend on `common-*` without repeating versions.

The build targets **Java 25**, the LTS, which is already the only JDK on this machine. Boot
4.1 supports 17 through 26, so the pin has headroom if it ever needs to move.

**Spring Cloud 2025.1.2 is the floor**, not just a suggestion: the 2025.1.x train started
against Boot 4.0.x and only gained Boot 4.1 compatibility at 2025.1.2. Pinning 2025.1.0 or
2025.1.1 against Boot 4.1.1 produces version-resolution failures that read like unrelated
bean errors. Take 2025.1.3.

Do **not** override Spring Boot's dependency management for Testcontainers. Check what Boot
4.1.1 actually pins (see its dependency-versions appendix) and take that; the Testcontainers
major lines have breaking API differences and a mismatch surfaces as confusing compile errors
in test code rather than as a dependency error.

### Boot 4 — verify these early

Boot 4 is Spring Framework 7 and Spring Security 7, so a handful of things this plan assumes
are worth confirming in the first hour rather than discovering in Phase 03. None is difficult;
all are annoying to hit blind:

- **Jackson major version.** Boot 4 may pin Jackson 3, whose package namespace moved. That
  affects `EventEnvelope` serialisation, the `JsonSerde` in `common-messaging`, and the
  `jsonb` payload mapping. Check what the BOM resolves and write the serde against that from
  the start — not against a Jackson 2 import copied from a tutorial.
- **Spring Security 7 configuration.** The non-lambda DSL is gone. Resource-server and
  `SecurityFilterChain` setup are lambda-only; anything else you find online will not compile.
- **Renamed and relocated properties.** Put `spring-boot-properties-migrator` on the test
  classpath for this phase and read what it reports, then remove it. It is the cheapest way
  to catch config that binds to nothing and fails silently.
- **Auto-configuration and starter coordinates.** Boot 4 reorganised some modules. Trust
  `start.spring.io` for the current coordinates over any existing example.

The general rule for this project, for the next year or so: when a Spring answer online
disagrees with the reference documentation, the documentation is right and the answer is
about Boot 3.

### `common-money`

The most important 200 lines in the project. Everything else depends on this being right.

```java
package com.revolutclone.money;

public record Money(long minor, Currency currency) implements Comparable<Money> {

    public static Money of(long minor, String currencyCode);
    public static Money ofMajor(BigDecimal major, String currencyCode);  // exact; throws on excess scale
    public static Money zero(String currencyCode);

    public Money plus(Money other);      // throws CurrencyMismatchException
    public Money minus(Money other);
    public Money negate();
    public Money abs();

    public boolean isZero();
    public boolean isPositive();
    public boolean isNegative();

    public Money[] allocate(int... ratios);   // largest-remainder, loses nothing
    public BigDecimal toMajor();
    @Override public String toString();       // "12.34 EUR"
}
```

Rules:
- `minor` is always in the currency's smallest unit, using
  `java.util.Currency.getDefaultFractionDigits()`. JPY has 0 digits, EUR/GBP/USD have 2 —
  the code must not assume 2 anywhere.
- Any binary operation on mismatched currencies throws `CurrencyMismatchException`. No
  implicit conversion, ever.
- `allocate` uses the largest-remainder method: distribute `minor * ratio[i] / sum`, then
  hand out the remaining units one at a time to the largest fractional parts. Splitting
  `100` three ways must give `[34, 33, 33]`, not `[33, 33, 33]`.

A separate `FxMath` holds the one place a rate is applied:

```java
public static Money convert(Money from, BigDecimal rate, String toCurrency);
// scales to target currency's fraction digits with RoundingMode.HALF_EVEN
```

### `contracts`

Event records only. No entities, no logic, no Spring. This is the one compile-time
dependency shared between services, so it must stay tiny and additive-only.

```java
public record EventEnvelope<T>(
    UUID eventId, Instant occurredAt, String correlationId,
    String type, int version, T payload) {}
```

Each event is a `record` implementing a marker interface, versioned by class name
(`PaymentInitiatedV1`). Evolution rule: **add optional fields only**. A breaking change
means a new `V2` record and a new topic suffix, with both consumed during migration.

### `common-messaging`

Named for what it does, not for the broker it currently uses. The module splits in two:

```
common-messaging/
├── api/      ports — no Spring Kafka dependency on the classpath here
│             EventPublisher, EventHandler<T>, OutboxAppender
└── spring/   the adapter — KafkaTemplate, @KafkaListener, error handlers
```

**The outbox is already the abstraction, and that is the point.** Business code calls
`OutboxAppender.append(topic, partitionKey, event)` inside its transaction and never touches
a broker; only the publisher speaks Kafka. The boundary was already in the right place — this
split just names it and keeps vendor types out of the signature. No `ProducerRecord`, no
`KafkaTemplate`, no Kafka headers in anything under `api/`.

Consumers implement `EventHandler<T>`. The `@KafkaListener` annotation lives in a thin
adapter class that delegates to the handler, which has the useful side effect of making every
handler unit-testable with no broker at all.

What this does **not** buy you is freedom from the log model — see §Portability boundaries in
the index. Partition-key ordering, consumer groups, replay from offset 0 and compaction are
load-bearing from Phase 03 onward and are not behind any interface.

Three things to build, all reusable by every service:

1. **Outbox.** A `@MappedSuperclass`-free plain JPA entity `OutboxEvent`
   (`id, aggregate_type, aggregate_id, type, payload jsonb, partition_key, topic,
   created_at, published_at, attempts`) plus an `OutboxAppender` services call inside their
   business transaction, and an `OutboxPublisher` `@Scheduled` poller:

   ```sql
   SELECT * FROM outbox_event
   WHERE published_at IS NULL
   ORDER BY id
   FOR UPDATE SKIP LOCKED
   LIMIT 100;
   ```

   `SKIP LOCKED` is what makes N replicas of a service safe. Flyway migration for the table
   ships as a classpath migration under `db/migration/common/` that each service includes.

   This query is one of the four places native Postgres SQL is allowed to live (see
   `phase-02`, §Portability boundary). Keep it here rather than letting `SKIP LOCKED` spread.

2. **Idempotent consumer.** `ProcessedEvent` entity with a `(consumer_group, event_id)`
   primary key, and an `IdempotentHandler` wrapper that inserts the row inside the handler's
   transaction and treats a duplicate-key violation as "already done, ack and move on".

3. **Error handling.** A `DefaultErrorHandler` bean with exponential backoff, three attempts,
   then publish to `<topic>.DLT`. Deserialization failures must go straight to the DLT
   without retry — otherwise one poison message blocks the partition forever.

### `common-web`

- `@RestControllerAdvice` producing RFC-7807 `ProblemDetail` responses with a stable
  `type` URI per error class.
- `CorrelationIdFilter`: read `X-Correlation-Id` or mint one, put it in MDC and in the
  response header.
- **Structured JSON logging to stdout**, with `correlationId` as a *field* rather than only a
  `%X{correlationId}` pattern. Same information, but queryable by a log aggregator later
  instead of only greppable. Use `logstash-logback-encoder` or Boot's own structured logging
  support; the `local` profile can keep a human-readable console pattern.
- springdoc OpenAPI config so every service exposes `/swagger-ui.html`.

### `common-security`

Two packages, because exactly two things here are Keycloak-specific and everything else is
standard OIDC:

```
common-security/
├── api/        IdentityProviderPort, UserPrincipal, @CurrentUser
└── keycloak/   KeycloakIdentityAdapter, realm fixture
```

- **Resource-server config** — validate JWT against the provider's JWKS. This is plain OIDC
  and needs no abstraction beyond `issuer-uri` being a config value.
- **`ConfigurableRolesClaimConverter implements Converter<Jwt, Collection<GrantedAuthority>>`**
  reading a dotted claim path from config rather than hardcoding it:

  ```yaml
  revolut.security.roles-claim: realm_access.roles
  ```

  `realm_access.roles` is a Keycloak invention with no basis in the OIDC spec, so it does not
  belong baked into `SecurityConfig`. About 30 lines.

- **`IdentityProviderPort`** — the seam over user provisioning, so Admin API calls do not
  spread through `identity-service`:

  ```java
  package com.revolutclone.security.api;

  public interface IdentityProviderPort {
      ProviderUser create(NewUser user);              // provider-assigned id
      void setPassword(String providerUserId, char[] password);
      void assignRole(String providerUserId, String role);
      Optional<ProviderUser> findByEmail(String email);
      void delete(String providerUserId);             // compensation path
      List<ProviderUser> listAll(int page, int size); // for the reconcile endpoint
  }
  ```

  `KeycloakIdentityAdapter` implements it, selected by `revolut.identity.provider=keycloak`
  via `@ConditionalOnProperty`. Keycloak is the only provider planned — the port is here as a
  design constraint, not in anticipation of a migration.

  The second implementation to write is `InMemoryIdentityAdapter`, and it is what actually
  repays the interface: Phase 01's Keycloak Testcontainer costs ~15 s per test class, and
  without a port the only alternative is mocking the admin client. With one, a broker-free
  fast path is a supported configuration.

- A `@CurrentUser` argument resolver yielding a `UserPrincipal(userId, email, roles)` so
  controllers never touch the raw `Jwt`.

### Infrastructure

`docker-compose.infra.yml` — infra only, so services can be run from the IDE against it:

| Service | Image | Port | Notes |
|---|---|---|---|
| postgres | `postgres:17` | 5432 | `init-databases.sql` creates 8 databases |
| kafka | `apache/kafka:3.9` | 9092 | KRaft single node, `auto.create.topics.enable=false` |
| kafka-ui | `provectuslabs/kafka-ui` | 8090 | |
| redis | `redis:7-alpine` | 6379 | idempotency cache |
| keycloak | `quay.io/keycloak/keycloak:26` | 8180 | `start-dev --import-realm` |

**Eight databases**, one per service that owns state, each with its own role: `identity`,
`account`, `ledger`, `payment`, `fx`, `card`, `analytics`, `notification`. The gateway and
the simulator own none. Keep them as separate *databases* with separate roles rather than
schemas — that way they map later to either eight schemas in one Aurora cluster or eight
instances, and the choice stays open.

**Two constraints borrowed from managed Postgres, adopted now because retrofitting them is
tedious:**

- **No superuser.** RDS and Aurora never grant one. Every Flyway migration must run as an
  ordinary table owner: no unguarded `CREATE EXTENSION`, and the Phase 02 constraint trigger
  created by the owning role. Set the local roles up as non-superuser so this is enforced
  from the first migration rather than discovered on a deploy.
- **TLS is config-driven.** Managed Postgres requires it. Make `sslmode` a property with
  `disable` set only in the `local` profile.

`auto.create.topics.enable=false` is deliberate: it forces topics to be declared, which is
how you'd run in production, and it surfaces typos immediately instead of silently creating
a topic with one partition.

**Why 12 partitions:** it is a highly composite number, so consumer groups of 1, 2, 3, 4, 6
or 12 instances all divide evenly with no skew. Partition count cannot be lowered later,
only raised — and raising it breaks key-to-partition affinity — so pick a number with room
and never change it. 12 is overkill for a laptop and exactly the point: Phase 07 scales
`ledger-service` to 3 replicas and you watch a clean 4-partitions-each assignment.

### Topic catalogue

`create-topics.sh` creates all of these as a one-shot container that exits. Cleanup policy
and retention are decided here because **neither can be changed cheaply later** — switching a
topic to compaction destroys the history that Phase 06 replays, and Phase 06 is where you
would find out.

| Topic | Key | Parts | Cleanup | Retention | Added |
|---|---|---|---|---|---|
| `identity.user.v1` | `userId` | 12 | compact | — | 01 |
| `account.account.v1` | `accountId` | 12 | compact | — | 01 |
| `ledger.entry.v1` | `ledgerAccountId` | 12 | delete | **`-1` (infinite)** | 02 |
| `payment.event.v1` | `paymentId` | 12 | delete | 30 d | 03 |
| `notification.push.v1` | `userId` | 12 | delete | 7 d | 03 |
| `fx.rate.tick.v1` | `pair` | 12 | delete | 1 h | 04 |
| `fx.quote.v1` | `quoteId` | 12 | delete | 30 d | 04 |
| `card.authorization.v1` | `cardId` | 12 | delete | **`-1` (infinite)** | 05 |

The two infinite-retention topics are the ones Phase 06 and Phase 07 replay from offset 0 to
rebuild the analytics projection. The two compacted topics are read-model sources, where only
the latest state per key matters and a fresh consumer needs to rebuild its view on startup.

Retry and DLT topics are added in Phase 07 with **3 partitions, not 12** — ordering no longer
matters once a message is in the retry chain, and three retry levels across ten topics is
otherwise a lot of partitions for one broker.

### Keycloak realm

This is the Keycloak adapter's fixture, so it lives with the adapter rather than being
treated as project-level configuration.

`revolut-realm.json` exported with: realm `revolut`, a public client `revolut-web` with
PKCE (`S256`), redirect URIs for `http://localhost:5173/*`, a confidential client
`revolut-backend` with service-account + `manage-users` for the Admin API, roles
`customer` and `admin`, and two seed users (`alice@demo.io`, `bob@demo.io`, password
`demo`). Keep it under version control and re-import on every `up` so the state is
reproducible.

## Task list

0. **Confirm the toolchain.** `java -version` reports 25, `mvn -version` reports the same JDK,
   and `JAVA_HOME` points at it. That last part is the one to actually do — `JAVA_HOME` is
   currently unset. Maven finds the JDK via `PATH` so the reactor builds either way, but IDE
   run configurations and some Maven plugins read `JAVA_HOME` directly, and an unset value
   produces failures that look like anything except a JDK problem.
1. `git init` (already done), `.gitignore` (Java, Node, IDE, `.env`), `.editorconfig`,
   `.gitattributes` with `*.sh text eol=lf`.
2. Root `pom.xml` — `spring-boot-starter-parent` 4.1.1, `java.version=25`,
   `spring-cloud-dependencies:2025.1.3` BOM import, module list. Then run the Boot 4
   verification list above before writing any code against it.
3. `platform/pom.xml` aggregator; `services/pom.xml` empty aggregator.
4. `common-money`: `Money`, `CurrencyMismatchException`, `FxMath`. Tests first.
5. `contracts`: `EventEnvelope`, marker interface, and the Phase 01–03 events as stubs.
6. `common-messaging`: the `api/` + `spring/` split; outbox entity + appender + publisher,
   `ProcessedEvent` + handler, error-handler config, `JsonSerde`, shared Flyway migration.
7. `common-web`: problem details, correlation filter, OpenAPI, structured JSON logging.
8. `common-security`: resource-server config, `ConfigurableRolesClaimConverter`,
   `IdentityProviderPort` + `KeycloakIdentityAdapter` + `InMemoryIdentityAdapter`,
   `@CurrentUser` resolver.
9. `infra/postgres/init-databases.sql` — 8 databases, one non-superuser role per service.
10. `docker-compose.infra.yml` + `infra/kafka/create-topics.sh` from the topic catalogue.
11. Keycloak realm export, stored with the Keycloak adapter.
12. `tasks.ps1` wrapper: `up`, `down`, `reset`, `logs <svc>`, `psql <db>`, `topics`.
13. Config hygiene pass: every endpoint from an environment variable, local defaults only in
    the `local` profile. Establish this now — it is far more annoying to retrofit across ten
    services than to start with.
14. `README.md` — architecture diagram, how to run, port table.

## Tests

| Test | Asserts |
|---|---|
| `MoneyArithmeticTest` | plus/minus/negate, zero handling, JPY 0-digit currency |
| `MoneyMismatchTest` | every binary op throws on mismatched currency |
| `MoneyAllocateTest` | `100` split 3 ways is `[34,33,33]`; sum always equals input; property test over random amounts and ratios |
| `MoneyParsingTest` | `ofMajor` rejects excess scale (`1.005 EUR` throws, never silently rounds) |
| `FxMathTest` | HALF_EVEN at the boundary; converting to a 0-digit currency |
| `EventEnvelopeSerdeTest` | round-trip for every contract record |
| `OutboxPublisherTest` | Testcontainers: two concurrent publishers never emit the same row twice |
| `IdempotentHandlerTest` | Testcontainers: same `eventId` delivered twice produces one side effect |
| `RolesClaimMappingTest` | The same JWT read with two different `roles-claim` paths yields the right authorities; a missing claim yields none rather than throwing |
| `IdentityProviderPortContractTest` | One suite, run against both `KeycloakIdentityAdapter` and `InMemoryIdentityAdapter` — create, find, assign role, delete, and duplicate-email behaviour identical in both |

`IdentityProviderPortContractTest` is what keeps the in-memory adapter honest. An in-memory
double that quietly diverges from the real thing is worse than no double at all, and a shared
contract suite is the cheapest way to stop that happening.

`MoneyAllocateTest` as a property test is worth the extra effort — it is the single easiest
place in the whole project to lose a cent.

## Verification

```powershell
.\tasks.ps1 up
docker compose -f docker-compose.infra.yml ps       # all healthy
.\tasks.ps1 topics                                  # lists every declared topic, 12 partitions
mvn -q clean verify                                 # reactor green, all module tests pass
```

- Kafka UI at <http://localhost:8090> shows the topic list and zero consumer groups.
- Keycloak admin at <http://localhost:8180> (`admin`/`admin`), realm `revolut` present with
  both seed users.
- A password-grant token request against
  `http://localhost:8180/realms/revolut/protocol/openid-connect/token` returns a JWT whose
  `realm_access.roles` contains `customer`.
- `.\tasks.ps1 topics --describe` shows `cleanup.policy` and `retention.ms` matching the
  catalogue — in particular `retention.ms=-1` on `ledger.entry.v1`. Check this now; Phase 06
  is an expensive place to discover it.
- `grep -ri localhost` across committed config returns hits only inside `local` profiles.

## Done when

- [ ] JDK 25 active and `JAVA_HOME` set; `mvn clean verify` green from the repo root
- [ ] Boot 4.1.1 + Spring Cloud 2025.1.3 resolve cleanly; Jackson major version confirmed and
      the serde written against it
- [ ] All five `common-*` modules install to the local repo
- [ ] `docker compose -f docker-compose.infra.yml up -d` → all containers healthy
- [ ] Every topic in the catalogue exists with 12 partitions and its declared cleanup policy
- [ ] Keycloak issues a valid JWT for a seed user
- [ ] Roles resolve through `ConfigurableRolesClaimConverter`, not a hardcoded claim path
- [ ] `IdentityProviderPort` has both a Keycloak and an in-memory implementation
- [ ] Eight databases exist, each owned by a non-superuser role
- [ ] No `localhost` outside a `local` profile; logs are JSON with a `correlationId` field
- [ ] `README.md` documents the port map and the run commands
- [ ] Money test suite covers mismatch, allocation and 0-digit currencies

## Risks & gotchas

- **Windows line endings in `create-topics.sh`.** A CRLF shell script fails inside a Linux
  container with a cryptic `\r: not found`. Add `*.sh text eol=lf` to `.gitattributes` now,
  not after an hour of debugging.
- **Keycloak `--import-realm` only imports on first start** with an empty database. A
  `tasks.ps1 reset` that drops the volume is essential; otherwise realm edits silently
  don't apply.
- **Docker Desktop port conflicts** — 5432 in particular if you have a local Postgres.
  Consider mapping 55432→5432 from the start rather than after the clash.
- **`jsonb` + JPA** needs an explicit type mapping. Either `@JdbcTypeCode(SqlTypes.JSON)` or
  store the payload as `text` and serialise manually. Decide once, in `common-messaging`, and
  be consistent. Confirm which Hibernate major version Boot 4.1 pins before choosing — this
  is one of the annotations most likely to differ from a Boot 3-era example.
- **Non-superuser roles will surface migration problems immediately** — which is the point,
  but expect one round of permission grants before Flyway runs clean. Cheaper here than on a
  first deploy to Aurora.
- **Do not let the port interfaces become a framework.** `IdentityProviderPort` and
  `EventPublisher` exist to keep two vendors' APIs out of business code, not to start a
  hexagonal-architecture layering exercise across ten services. If a port grows a method that
  exists only to satisfy an imagined second implementation, delete it.
- **Resist adding a business service in this phase.** The temptation is strong and it always
  results in half the shared libraries being shaped around one service's accident.
