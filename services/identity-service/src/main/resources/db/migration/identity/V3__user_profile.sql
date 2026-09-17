-- plans/phase-01-identity-account-gateway.md, ### identity-service. id mirrors the provider
-- (Keycloak) user id - identity-service is not the id's authority, Keycloak is.
--
-- Starts at V3, not V1: Flyway resolves all `flyway.locations` into one flat version
-- sequence, so V1 and V2 are reserved project-wide for common-messaging's shared
-- db/migration/common/V1__outbox_event.sql and V2__processed_event.sql. See
-- docs/spring-boot-4-notes.md.
create table user_profile (
    id             uuid primary key,
    email          text not null unique,
    first_name     text not null,
    last_name      text not null,
    country        char(2) not null,
    phone          text,
    status         text not null,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null
);
