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

## Still to verify when first needed

Append here as these get settled, with sources:

- Actuator endpoint paths and the health-group config shape in Boot 4.1.
- Whether `spring-kafka` 4.1's `DefaultErrorHandler` / `DeadLetterPublishingRecoverer` API
  changed (phase 07's retry and DLT chain depends on it).
- Springdoc/OpenAPI version compatible with Boot 4.1 (phase 00 task 7, `common-web`).
- React 19 + Vite specifics are not a Spring concern and belong in the phase 03b notes.
