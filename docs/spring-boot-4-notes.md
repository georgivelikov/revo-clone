# Spring Boot 4.1 — verified facts

Boot 4.1 shipped in June 2026, after the model's training cutoff, and brings Spring Framework
7 and Spring Security 7. Most Spring material online still targets Boot 3. **This file is the
authority for stack API questions.** Every entry was verified against the published artifact
or the reference documentation, and records where it came from.

**Rules for this file.** Nothing goes in without a source. If you need a fact that is not
here, fetch it from `docs.spring.io` (or the published POM), use it, and append it here with
its source. Never guess an API shape or a coordinate.

Verified 2026-09-13 against `spring-boot-dependencies:4.1.1` and
`spring-cloud-dependencies:2025.1.3`.

## Managed versions

From `spring-boot-dependencies-4.1.1.pom` on `repo1.maven.org` — these are the versions Boot
pins, so never set them yourself:

| Property | Version | Notes |
|---|---|---|
| `spring-framework.version` | 7.0.9 | |
| `spring-security.version` | 7.1.1 | |
| `jackson-bom.version` | **3.1.5** | Jackson 3 — see below, this one changes code |
| `jackson-2-bom.version` | 2.21.5 | present only for migration |
| `hibernate.version` | 7.4.5.Final | |
| `hibernate-validator.version` | 9.1.3.Final | |
| `kafka.version` | 4.2.1 | Kafka clients |
| `spring-kafka.version` | 4.1.1 | |
| `flyway.version` | 12.4.0 | |
| `postgresql.version` | 42.7.13 | |
| `testcontainers.version` | **2.0.5** | Testcontainers 2 — coordinates changed, see below |
| `micrometer.version` | 1.17.1 | |
| `micrometer-tracing.version` | 1.7.1 | |

## Jackson 3 is the default — this changes every serde and DTO

Source: <https://docs.spring.io/spring-boot/reference/features/json.html>, and
`tools.jackson.core:jackson-databind:3.1.5` confirmed present on Maven Central.

- **Boot 4 auto-configures Jackson 3.** The docs say plainly: *"Jackson 3 is the preferred and
  default library."*
- **The package root is `tools.jackson`, not `com.fasterxml.jackson`.** The Maven groupId
  changed too: `tools.jackson.core`, not `com.fasterxml.jackson.core`.
- **The mapper is `JsonMapper`, not `ObjectMapper`.** Build it with the `JsonMapper` builder.
- Starter: `spring-boot-starter-json`.
- **Jackson 2 is deprecated** and *"will be removed in a future Spring Boot 4.x release. It is
  provided purely to ease the migration."* It lives in the `spring-boot-jackson2` module, and
  you opt in per-surface with `spring.http.converters.preferred-json-mapper=jackson2` (MVC) or
  `spring.http.codecs.preferred-json-mapper=jackson2` (WebFlux).

**Consequence for this project: write `JsonSerde`, `EventEnvelope` and every DTO against
Jackson 3 from the start.** Do not add `spring-boot-jackson2`. Any import beginning
`com.fasterxml.jackson` is a mistake — `plans/phase-00-foundations.md` §Risks flags this as
"confirm which Jackson major version Boot 4.1 pins before choosing"; it is Jackson 3.

## Spring Security 7 — resource server DSL is unchanged from 6

Source: <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html>

Good news: the JWT resource-server surface did **not** break between 6 and 7. The lambda DSL
is the same shape, so Boot 3-era examples of *this specific config* are still structurally
right.

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/public/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }
}
```

- Custom roles claim: `JwtGrantedAuthoritiesConverter` with `setAuthoritiesClaimName(...)` and
  `setAuthorityPrefix("ROLE_")`, wrapped in a `JwtAuthenticationConverter` via
  `setJwtGrantedAuthoritiesConverter(...)`. For full control, implement
  `Converter<Jwt, AbstractAuthenticationToken>` and pass it to `jwtAuthenticationConverter`.
- `ConfigurableRolesClaimConverter` (phase 00 task 8) is a thin wrapper over
  `JwtGrantedAuthoritiesConverter` with the claim path read from configuration. Keycloak nests
  roles at `realm_access.roles`, which `setAuthoritiesClaimName` cannot reach on its own — a
  nested path needs a custom converter reading `jwt.getClaimAsMap("realm_access")`.
- Properties: `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `.jwk-set-uri`,
  `.audiences`.

## Coordinates that changed — verified by HTTP 200/404 on Maven Central

These are the ones an agent will get wrong from memory. Each was checked against
`repo1.maven.org` directly.

| Need | Correct coordinate | The Boot 3-era name, now **404** |
|---|---|---|
| Spring Cloud Gateway (MVC) | `spring-cloud-starter-gateway-server-webmvc` | `spring-cloud-starter-gateway` |
| Spring Cloud Gateway (reactive) | `spring-cloud-starter-gateway-server-webflux` | `spring-cloud-starter-gateway` |
| Testcontainers Postgres | `org.testcontainers:testcontainers-postgresql` | `org.testcontainers:postgresql` |
| Testcontainers Kafka | `org.testcontainers:testcontainers-kafka` | `org.testcontainers:kafka` |
| Testcontainers JUnit 5 | `org.testcontainers:testcontainers-junit-jupiter` | `org.testcontainers:junit-jupiter` |
| Jackson databind | `tools.jackson.core:jackson-databind` | `com.fasterxml.jackson.core:jackson-databind` |

**Testcontainers 2.x prefixes every module artifactId with `testcontainers-`.** Core is still
`org.testcontainers:testcontainers`; `@Testcontainers` and `@Container` are unchanged
(<https://java.testcontainers.org/quickstart/junit_5_quickstart/>). Boot's integration module
is `spring-boot-testcontainers`, confirmed at 4.1.1.

## Spring Cloud 2025.1.3

All components are version 5.0.x (gateway 5.0.3, circuitbreaker 5.0.3, commons 5.0.3,
openfeign 5.0.3, config 5.0.5). `spring-cloud-starter-circuitbreaker-resilience4j:5.0.3`
confirmed present — that is the circuit breaker phase 04 wraps the `payment → fx` call in.

**Compatibility note worth knowing:** `spring-cloud-dependencies-2025.1.3.pom` declares its own
`<spring-boot.version>` as **4.0.8**, not 4.1.1. With `spring-boot-starter-parent` as the
parent and `spring-cloud-dependencies` imported into `dependencyManagement`, Boot's parent wins
for every overlapping artifact, so this is expected rather than broken. But it is the first
thing to check if a version conflict appears, and it means 2025.1.3 was built and tested
against Boot 4.0.x.

## Hibernate 7.4 and `jsonb`

Hibernate 7.4.5.Final. `@JdbcTypeCode(SqlTypes.JSON)` is available (it has existed since
Hibernate 6) and is the mapping to use for the outbox payload column. Decide once in
`common-messaging` and stay consistent, per `plans/phase-00-foundations.md` §Risks.

## Flyway 12

`flyway-core` alone is not enough for Postgres — since Flyway 10 each database has its own
module. Add **`org.flywaydb:flyway-database-postgresql`** (confirmed at 12.4.0) alongside
`flyway-core`, or migrations fail at runtime with "no database support".

**`flyway-core` alone is also not enough to make Boot run Flyway at all.** Boot 4.1 split
`FlywayAutoConfiguration` out of `spring-boot-autoconfigure` into its own module,
`org.springframework.boot:spring-boot-flyway` (confirmed present on Maven Central at 4.1.1 via
`mvn dependency:get`; version managed by `spring-boot-dependencies`, no version needed in the
dependency declaration). Without it, Flyway migrations are silently never applied — no error,
no log line, just missing tables surfacing later as a Hibernate schema-validation failure
("missing table [...]") that looks like a migration-content bug rather than a missing-module
one. Same pattern as the `spring-boot-persistence` split below: assume Boot 4.1 modularized
autoconfiguration per concern, and verify by resolving the artifact rather than assuming a
Boot-3-era single `spring-boot-autoconfigure` jar has everything.

## Boot 4.1 split Kafka autoconfiguration out too: spring-boot-kafka

Source: `spring-boot-kafka-4.1.1.jar` (confirmed present on Maven Central at 4.1.1 via
`mvn dependency:get`). Same pattern as `spring-boot-flyway`: `spring-kafka` alone puts the
Spring Kafka library on the classpath but does not activate Boot's `KafkaAutoConfiguration`
(the `KafkaTemplate`, consumer/producer factory beans, `spring.kafka.*` property binding).
Without `org.springframework.boot:spring-boot-kafka` as an explicit dependency, injecting
`KafkaTemplate` fails with `NoSuchBeanDefinitionException` and nothing in the log explains why.

Also note: Boot's auto-configured `KafkaTemplate` bean is typed `KafkaTemplate<Object, Object>`,
not `<String, String>` - Spring's generic-aware autowiring only matches an injection point
whose generic parameters agree exactly with the `@Bean` method's declared return type, so a
constructor asking for `KafkaTemplate<String, String>` also fails to match even once this
module is present. Depend on `KafkaTemplate<Object, Object>` (or define your own bean) rather
than narrowing the generics.

## Boot 4.1 split persistence autoconfiguration out of spring-boot-autoconfigure

Source: `spring-boot-persistence-4.1.1.jar` (resolved locally via `spring-boot-starter-data-jpa`;
confirmed by unzipping the jar and finding the class).

**`@EntityScan` moved.** It is no longer `org.springframework.boot.autoconfigure.domain.EntityScan`
(that package doesn't exist in `spring-boot-autoconfigure-4.1.1.jar` at all) - it is
`org.springframework.boot.persistence.autoconfigure.EntityScan`, shipped in the new
`spring-boot-persistence` module that `spring-boot-starter-data-jpa` pulls in transitively.
Expect other JPA-adjacent autoconfiguration classes to have moved into this module too; verify
by unzipping the jar rather than guessing if one goes missing.

## Spring Kafka 4.1: `ExponentialBackOffWithMaxRetries` moved to `.support`

Source: `spring-kafka-4.1.1.jar` (resolved locally; confirmed by unzipping the jar). It is
`org.springframework.kafka.support.ExponentialBackOffWithMaxRetries`, not
`org.springframework.kafka.listener.ExponentialBackOffWithMaxRetries` (a Boot-3-era location
that no longer exists in 4.1.1). `DefaultErrorHandler` and `DeadLetterPublishingRecoverer`
themselves are still at their expected `org.springframework.kafka.listener` package.

## Boot 4.1's own structured JSON logging needs no extra dependency

Source: <https://docs.spring.io/spring-boot/reference/features/logging.html>

`logging.structured.format.console=logstash` (or `ecs`, or `gelf`) turns on JSON logging to
stdout with **no `logstash-logback-encoder` dependency required** — it's built into
`spring-boot` itself. MDC key-value pairs (so `correlationId`, once
`CorrelationIdFilter` puts it there) are folded into the JSON output automatically as real
fields, not just a pattern token. `common-web` (phase 00 task 7) defaults every service that
depends on it to `logstash` format via a low-precedence `EnvironmentPostProcessor`, so a
service's own `application.yml` — in particular the `local` profile, which wants a
human-readable console pattern instead — can override it with no extra wiring.

## springdoc-openapi 3.1.1 targets Boot 4.1

Source: `springdoc-openapi-starter-webmvc-ui-3.1.1.pom` and its parent
`springdoc-openapi-3.1.1.pom` on `repo1.maven.org` — the parent's own `<parent>` is
`spring-boot-starter-parent:4.1.0`. springdoc's 2.x line targets Spring 6/Boot 3; **3.x is the
Boot 4/Framework 7 line.** Not part of any Spring BOM, so it's pinned explicitly in
`common-web/pom.xml` rather than left to `spring-boot-dependencies`. Adding the
`springdoc-openapi-starter-webmvc-ui` dependency is sufficient on its own — it ships its own
auto-configuration and exposes `/swagger-ui.html` and `/v3/api-docs` with zero extra config;
`common-web`'s `WebAutoConfiguration` only adds a default `OpenAPI` bean to set the displayed
title/version from `spring.application.name`.

## Keycloak: no bare `26` image tag; admin client is `org.keycloak:keycloak-admin-client`

Source: `quay.io/api/v1/repository/keycloak/keycloak/tag/` (queried directly - the bare `26`
tag phase-00's infrastructure table names does not exist on `quay.io/keycloak/keycloak`; only
fully- or minor-qualified tags do, e.g. `26.7`, `26.7.3`). **Use `26.7`** (latest patch of the
26.x line, and the version `keycloak-admin-client` 26.0.x/`testcontainers-keycloak` 4.3.1 were
built and tested against) for both `docker-compose.infra.yml` (task 00.10) and any
`KeycloakContainer` in tests - not the bare `26` the phase table shows as shorthand.

Admin REST client: `org.keycloak:keycloak-admin-client` (confirmed present on Maven Central up
to `26.0.12` via `maven-metadata.xml`); pinned explicitly since it isn't part of any Spring/Boot
BOM. **No official `org.testcontainers` Keycloak module exists** (confirmed 404 on
`repo1.maven.org`); the de facto standard is the community module
`com.github.dasniko:testcontainers-keycloak` (latest `4.3.1`), whose own POM pins
`org.testcontainers:testcontainers:2.0.5` - matching Boot 4.1.1's managed Testcontainers
version exactly, so no conflict. Its `KeycloakContainer` (package `dasniko.testcontainers.keycloak`,
confirmed by inspecting the jar) exposes `getKeycloakAdminClient()`, an already-authenticated
`Keycloak` admin client against the container's `master` realm - simpler than building one by
hand with `KeycloakBuilder`.

## Spring Cloud Gateway 5.0.3 (webflux): Java package roots didn't move, only the artifact and property prefix did

Source: unzipped `spring-cloud-gateway-server-webflux-5.0.3.jar` directly (task 01.1) rather
than trusting a Boot-3-era tutorial. The `-server-webflux`/`-server-mvc` split (phase-00's
notes already flagged the artifact rename: use `spring-cloud-starter-gateway-server-webflux`,
never the deprecated `spring-cloud-starter-gateway`) only changed the **artifact id** and the
**property prefix** (`spring.cloud.gateway.server.webflux.*`). The **Java API package roots
are unchanged from the Boot-3-era gateway**: `org.springframework.cloud.gateway.route.RouteLocator`,
`org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder`,
`org.springframework.cloud.gateway.filter.ratelimit.{KeyResolver,RedisRateLimiter,PrincipalNameKeyResolver}`
all still exist at those exact paths in 5.0.3 — confirmed by listing the jar's entries, not by
assumption. `RedisRateLimiter` is auto-configured by `GatewayRedisAutoConfiguration` (one of
the `AutoConfiguration.imports` entries in the starter jar) once
`spring-boot-starter-data-redis-reactive` is on the classpath — no manual bean needed unless
you want to override its defaults. Its own `@ConfigurationProperties` prefix **did** move
under the new prefix (confirmed by grepping the class's own constant pool):
`spring.cloud.gateway.server.webflux.redis-rate-limiter.replenish-rate` /
`.burst-capacity` / `.requested-tokens` — not the bare `spring.cloud.gateway.redis-rate-limiter.*`
a Boot-3-era example would use.

## Boot 4.1 split WebTestClient auto-configuration into its own module: `spring-boot-webflux-test`

Source: <https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html>,
confirmed present at `spring-boot-webflux-test:4.1.1` via a direct HTTP 200 on
`repo1.maven.org`. `@AutoConfigureWebTestClient` is **not** in `spring-boot-test-autoconfigure`
(that jar shrank drastically in 4.1 — it now holds only a handful of cross-cutting classes plus
the `json` test slice) and is **not** pulled in transitively by `spring-boot-starter-test`.
Same "modularized per concern, verify by resolving the artifact" pattern as
`spring-boot-persistence`/`spring-boot-flyway`/`spring-boot-kafka` above. Package also moved:
`org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient`, not
`org.springframework.boot.test.autoconfigure.web.reactive` (the Boot-3-era location, which no
longer exists at all in 4.1.1).

## Excluding a Servlet-shaped auto-configuration from a WebFlux-only service needs `excludeName`, not `exclude`

Not a Boot 4 API change, but a real trap hit building `api-gateway` (task 01.1) that is worth
recording since this project will reuse `common-security`/`common-web` from WebFlux services
again. If a service excludes `spring-boot-starter-web` to stay WebFlux-only but still depends
on a library module (like this project's `common-security`) whose auto-configuration class
`implements WebMvcConfigurer` or otherwise references a Servlet-only type,
`@SpringBootApplication(exclude = {ThatAutoConfiguration.class})` **fails at context startup**
with either `NoClassDefFoundError` deep in `ConfigurationClassParser` or the confusingly
unrelated-looking `IllegalStateException: No auto-configuration attributes found` — because a
`Class<?>` literal in an annotation attribute is resolved (and the class loaded and verified)
eagerly by the JVM, and loading a class that `implements WebMvcConfigurer` fails when
`spring-webmvc` isn't on the classpath at all.

**Fix: use `excludeName = {"fully.qualified.ClassName"}` (a `String[]`) instead of
`exclude = {ClassName.class}`.** `excludeName` never classloads the excluded class, so it works
even when that class literally cannot be loaded on this classpath.

## `RequestRateLimiter` gateway filter has no Java DSL - YAML only

Source: <https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html>,
and confirmed empirically (task 01.1): `GatewayFilterSpec.requestRateLimiter(c ->
c.setRateLimiter(redisRateLimiter).setKeyResolver(userKeyResolver))` compiles and runs, but
every request through that route throws `IllegalArgumentException: No Configuration found for
route <id> or defaultFilters` at request time — the Java DSL never registers the per-route
`Config` into `RedisRateLimiter`'s internal map the way the YAML-driven filter-factory path
does. The docs say this outright: *"The `RequestRateLimiter` is not configurable with the
'shortcut' notation"* and show no Java DSL example at all — YAML is the only supported route
of configuration for this specific filter. Define these routes under
`spring.cloud.gateway.server.webflux.routes` (note the prefix, per the note above) with:

```yaml
filters:
  - name: RequestRateLimiter
    args:
      redis-rate-limiter.replenishRate: 10
      redis-rate-limiter.burstCapacity: 20
      redis-rate-limiter.requestedTokens: 1
      key-resolver: "#{@userKeyResolver}"
```

`key-resolver` is a SpEL reference to a `KeyResolver` `@Bean` (still ordinary Java) - only the
route/filter wiring itself needs to be YAML.

## Boot 4.1 split MockMvc auto-configuration into its own module too: `spring-boot-webmvc-test`

Source: `spring-boot-dependencies-4.1.1.pom` (confirmed `spring-boot-webmvc-test` and
`spring-boot-starter-webmvc-test` both listed there; `spring-boot-webmvc-test-4.1.1.jar`
resolved and inspected directly, task 01.2). Same split as `spring-boot-webflux-test`
(`@AutoConfigureWebTestClient`, above) but for the Servlet side: `@AutoConfigureMockMvc` is
**not** in `spring-boot-test-autoconfigure` (that jar has none of the servlet/mockmvc classes
at all in 4.1.1 — confirmed by listing its contents) and is **not** pulled in transitively by
`spring-boot-starter-test`. A service written as a normal Servlet MVC app (like
`identity-service`) that wants `@AutoConfigureMockMvc` must add
`org.springframework.boot:spring-boot-webmvc-test` as an explicit test-scope dependency.
Package: `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` — not
`org.springframework.boot.test.autoconfigure.web.servlet` (the Boot-3-era location, which no
longer exists in 4.1.1). Expect this "modularized per concern, verify by resolving the
artifact" pattern to keep recurring for any `@AutoConfigure*` annotation Boot 3 shipped as part
of one monolithic `spring-boot-test-autoconfigure`.

## `src/test/resources/application.yml` REPLACES `src/main/resources/application.yml` for tests, it does not merge

Not a Boot 4 change - this is long-standing Spring Boot behaviour, but it cost real time on
task 01.1 and is worth recording since the instinct to add a small test-only
`application.yml` with a few extra defaults is natural and wrong. Boot resolves the default
`classpath:/application.yml` config-data location as a single resource lookup; when both
`target/classes/application.yml` (main) and `target/test-classes/application.yml` (test) exist
on the test classpath, **the test one is used instead of, not in addition to, the main one** -
main's entire config, including anything as central as `spring.cloud.gateway.*.routes`, becomes
invisible to every test. It also has a nasty second-order effect: `mvn test` (without `clean`)
does not delete a `target/test-classes/application.yml` whose source file you removed, so the
shadowing can persist and cause confusing failures even after you think you've undone it -
always `mvn clean` when suspecting this.

**Use `@TestPropertySource(properties = {...})` (or `@DynamicPropertySource` for values only
known at runtime, e.g. a Testcontainers port) to add or override individual keys instead.**
Those layer on top of the real `application.yml` rather than replacing it. Reach for a real
`application-test.yml` file only if you also activate it as a genuine Spring profile
(`@ActiveProfiles`) - profile-specific files overlay the base one; same-named default files do
not.

## Flyway merges every `flyway.locations` entry into one flat version sequence — V1 and V2 are reserved for the shared migrations

Confirmed empirically (task 01.2): with
`flyway.locations: classpath:db/migration/common,classpath:db/migration/<service>`
(the `new-service` skill's own template), Flyway does **not** version each location
independently — it resolves migrations from every location into a single ordered sequence
keyed by version number, and fails startup with `FlywayException: Found more than one
migration with version N` if two locations each contain a migration with the same version
number. `common-messaging`'s shared migrations are `db/migration/common/V1__outbox_event.sql`
and `V2__processed_event.sql` (from phase 00), so **every service's own first migration must
start at `V3`, never `V1` or `V2`** — those two versions are reserved project-wide for the
shared migrations. `identity-service`'s `V3__user_profile.sql` is the first example of this;
every service scaffolded after it needs the same offset.

## `common-messaging`'s `@EntityScan`/`@EnableJpaRepositories` disable Boot's own auto-detection for the WHOLE consuming service, not just its own packages

Confirmed empirically (task 01.2), and **neither annotation is additive with Boot's default
behavior the way it's easy to assume** — this cost two rounds of debugging on the same wrong
assumption, worth stating plainly: `common-messaging`'s `MessagingAutoConfiguration` declares
its own `@EntityScan(basePackageClasses = {OutboxEvent.class, ProcessedEvent.class})` and
`@EnableJpaRepositories(basePackageClasses = {OutboxEventRepository.class,
ProcessedEventRepository.class})` to wire its own entities/repositories into any service that
depends on the module.

- `DataJpaRepositoriesAutoConfiguration` (Boot's default repository scan) is
  `@ConditionalOnMissingBean` on `JpaRepositoryFactoryBean` — once *any* `@EnableJpaRepositories`
  fires anywhere in the context, even one scoped to totally unrelated packages, Boot backs off
  its own scanning **entirely**, for every package. A service's own `XxxRepository` interface
  then silently never becomes a bean — no error at startup, just a `NoSuchBeanDefinitionException`
  the first time something autowires it.
- Once that's fixed by adding the service's own `@EnableJpaRepositories`, the *next* failure is
  `Not a managed type: class ...` — because `@EntityScan` has the exact same
  once-any-appears-Boot's-default-is-gone behavior. Boot's implicit entity scan (from the
  `@SpringBootApplication` package down) only runs when **no** `@EntityScan` exists anywhere in
  the context; `common-messaging`'s own `@EntityScan` means the service's own entities are never
  registered with Hibernate unless the service adds its own `@EntityScan` too.

**Every service that depends on `common-messaging` must declare its own
`@EntityScan(basePackageClasses = ...)` AND `@EnableJpaRepositories(basePackageClasses = ...)`**,
both covering its own domain/repository classes, typically stacked on the
`@SpringBootApplication` class alongside `common-messaging`'s own declarations. Multiple
declarations of either annotation with disjoint base packages coexist fine in one context —
this is not a workaround, it's the supported pattern for a shared library module plus a host
application each owning their own scan scope. `identity-service`'s `IdentityServiceApplication`
is the first example (both annotations, `UserProfile`/`UserProfileRepository`); every service
scaffolded after it needs the same pair, and the `new-service` skill's claim that including
`common-messaging` needs "no per-service entity-scan or repository config" is wrong for both
annotations, not just one.

## Spring Security's Servlet resource-server default leaves CSRF ON, which 403s a bearer-token API

Not a Boot 4 change, but a real gap found finishing `common-security`'s own default filter
chain against a real service (task 01.2): Spring Security's `HttpSecurity` DSL enables CSRF
protection by default whether or not `oauth2ResourceServer(...).jwt(...)` is configured. CSRF
defends session/cookie-authenticated browser clients, which none of this project's services
are — every one is a stateless bearer-JWT resource server, so there is no session and no
cookie to steal a token from. Left on, it rejects every non-`GET` request with a bare `403
Forbidden` (no body, no exception in the handler chain — it never reaches a controller), which
looks exactly like an authorization bug rather than what it is. It also blocks
`/api/identity/register`, phase 01's one intentionally *unauthenticated* route, since CSRF
runs before the authorization/authentication filters even get a say.

**Every `SecurityFilterChain` bean in this project — `common-security`'s own default
(`SecurityAutoConfiguration`) and any service's override (e.g.
`identity-service`'s `SecurityConfig`) — must explicitly**:
```java
http.csrf(csrf -> csrf.disable())
    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```
`common-security`'s default now does this; a service overriding the whole filter chain (rather
than composing with the default) must repeat it, since overriding replaces the bean entirely
rather than layering on top of it.

## Testcontainers' Kafka module (`org.testcontainers:testcontainers-kafka`) does not start on Docker Desktop for Windows

Not a Spring/Boot fact, but a real environment limitation hit writing an automated regression
test for task 01.4 (proving `identity-service` actually publishes `UserRegisteredV1` to a real
broker, not just an outbox row). Both `org.testcontainers.kafka.KafkaContainer` (tried against
`apache/kafka:3.9.0`, the image `docker-compose.infra.yml` itself uses) and
`org.testcontainers.kafka.ConfluentKafkaContainer` (tried against `confluentinc/cp-kafka:7.7.1`)
fail identically: `Timed out waiting for log output matching '.*Transitioning from RECOVERY to
RUNNING.*'`, with **no container stdout/stderr at all** — the container never produces output,
which points at the container failing to start rather than starting slowly.

`docker info` on this machine confirms why: `Operating System: Docker Desktop`,
`Kernel Version: ...-microsoft-standard-WSL2`. Both Testcontainers Kafka container classes rely
on `--network=host` so the broker's advertised listener can point straight at `localhost` for
the host JVM. Docker Desktop's `host` network driver does not give a container the real host
network namespace the way native Linux Docker does — it is scoped to the WSL2 VM instead — so
the broker's listener never binds where the wait strategy (and the test JVM) expect it, and the
container appears to hang with no logs.

**Consequence:** an automated test that spins up its own Kafka broker via
`testcontainers-kafka` will not run on this machine (Docker Desktop + WSL2). This does not
affect `OutboxPublisherTest` (`common-messaging`, phase 00 task 6) or
`RegisterUserIntegrationTest` (phase 01 task 2) — neither touches a real broker; the first uses
a `RecordingEventPublisher` fake and the second sets `poll-delay-ms` to five minutes so the
poller never fires during the test. The actual `KafkaEventPublisher` → real-broker path has
instead been verified **manually**, end to end, against the live `docker-compose.infra.yml`
stack (task 01.4): registered three real users, waited for the `OutboxPublisher`'s real
scheduled poll, and consumed `identity.user.v1` directly via
`docker exec revolut-kafka kafka-console-consumer.sh --property print.partition=true --property
print.key=true` — confirming each message's key is the user id, each landed on a different
partition (11, 6, 3 — the default partitioner hashing distinct keys), and every
`EventEnvelope` field (`eventId`, `occurredAt`, `correlationId`, `version`) and
`UserRegisteredV1` payload field were present and correct.

If a later phase needs an automated broker-backed test on this kind of environment, look at
either running the test suite inside a Linux container/CI runner (where `--network=host` behaves
normally), or wiring the test against the already-running `docker-compose.infra.yml` broker via
`spring.kafka.bootstrap-servers=127.0.0.1:9092` instead of a fresh Testcontainers broker —
deliberately not done here, since that would make `mvn clean verify` depend on external state
being up, breaking the reactor's self-containment guarantee that every other test in this
project relies on.

## Still to verify when first needed

Append here as these get settled, with sources:

- Actuator endpoint paths and the health-group config shape in Boot 4.1.
- Whether `spring-kafka` 4.1's `DefaultErrorHandler` / `DeadLetterPublishingRecoverer` API
  changed (phase 07's retry and DLT chain depends on it).
- React 19 + Vite specifics are not a Spring concern and belong in the phase 03b notes.
