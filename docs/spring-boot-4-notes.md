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

## Still to verify when first needed

Append here as these get settled, with sources:

- Actuator endpoint paths and the health-group config shape in Boot 4.1.
- Whether `spring-kafka` 4.1's `DefaultErrorHandler` / `DeadLetterPublishingRecoverer` API
  changed (phase 07's retry and DLT chain depends on it).
- React 19 + Vite specifics are not a Spring concern and belong in the phase 03b notes.
