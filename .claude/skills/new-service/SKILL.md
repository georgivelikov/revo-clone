---
name: new-service
description: Scaffold a Spring Boot service to this project's conventions — module pom, env-var-only application.yml with a separate local profile file, Flyway directories including the shared migration, common-* dependencies, resource-server config, Docker entry, port from the map, health check. Use whenever a task creates a new service under services/.
---

# Scaffold a service

Used nine times across phases 01–06. Writing it once is the main defence against drift
between services, so follow it exactly rather than copying whichever service you looked at
last.

Check `docs/spring-boot-4-notes.md` before writing any dependency or Spring config. Boot 4.1
renamed coordinates that are commonly mis-remembered, and the notes file records the verified
ones.

## 1. Port and name

Take the port from the map in `CLAUDE.md` — never pick one. Module path is
`services/<name>-service`, package root `com.revolutclone.<name>`.

## 2. Module pom

Parent is the root `pom.xml`. Add the module to `services/pom.xml`.

Dependencies: `spring-boot-starter-web`, `spring-boot-starter-validation`,
`spring-boot-starter-actuator`, `spring-boot-starter-data-jpa`, `flyway-core` **plus
`flyway-database-postgresql`** (Flyway 10+ needs the per-database module or migrations fail at
runtime), `postgresql`, and the project's own `common-money`, `common-web`, `common-security`,
`common-messaging`, `contracts` as the task requires.

Tests: `spring-boot-starter-test`, `spring-boot-testcontainers`, and Testcontainers modules
by their **2.x names** — `testcontainers-postgresql`, `testcontainers-kafka`,
`testcontainers-junit-jupiter`. The unprefixed 1.x names do not exist.

## 3. `application.yml` — environment variables only

Every endpoint comes from an environment variable. No `localhost` anywhere in this file.

```yaml
server:
  port: ${SERVER_PORT:8083}
spring:
  application:
    name: <name>-service
  datasource:
    url: ${<NAME>_DB_URL}
    username: ${<NAME>_DB_USER}
    password: ${<NAME>_DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate        # Flyway owns the schema. Never raise this.
  flyway:
    locations: classpath:db/migration/common,classpath:db/migration/<name>
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${OIDC_ISSUER_URI}
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true
```

## 4. `application-local.yml` — a separate file, not a profile document

Local defaults go in their own `application-local.yml`. Do **not** add a
`on-profile: local` document inside `application.yml`: the tripwire checks this by filename
and a `local` document in the main file reads as a violation.

## 5. Flyway

Create `src/main/resources/db/migration/<name>/` for this service's own migrations, and make
sure the shared `db/migration/common/` (the outbox and `processed_event` tables) is on the
`locations` list above. Migrations run as an ordinary owner role, never a superuser.

## 6. Security

Use `common-security`'s resource-server configuration and
`ConfigurableRolesClaimConverter` — never a hardcoded claim path. Keycloak nests roles at
`realm_access.roles`.

## 7. Docker

Add the service to `docker-compose.yml` under the `services` profile, with its port from the
map, its environment variables, a `depends_on` for Postgres and Kafka, and a healthcheck
hitting `/actuator/health/readiness`.

## 8. Confirm before you finish

- `mvn -q clean verify` green from the repo root
- the service starts against `docker-compose.infra.yml` and `/actuator/health` returns UP
- `grep -ri localhost` over the new files hits only `application-local.yml`
- the shared Flyway migration applied into this service's own database
