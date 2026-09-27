-- plans/phase-05-cards.md, ### card-service. Starts at V3, not V1: Flyway resolves all
-- `flyway.locations` entries into one flat version sequence, so V1 and V2 are reserved
-- project-wide for common-messaging's shared db/migration/common/V1__outbox_event.sql and
-- V2__processed_event.sql. See docs/spring-boot-4-notes.md.
--
-- Uniqueness is on pan_hash, not pan_last4: a unique index on (pan_last4, expiry_month,
-- expiry_year) is global across every user and draws from only 10,000 last-4 values, so
-- issuance would start failing with spurious collisions long before the real PAN space was
-- anywhere near exhausted. pan_hash is a keyed HMAC-SHA256 (see PanHasher), not a bare hash,
-- so the column is not itself a brute-forceable index of card numbers.
create table card (
    id                uuid primary key,
    user_id           uuid not null,
    account_id        uuid not null,
    pan_hash          bytea not null,
    pan_last4         char(4) not null,
    pan_encrypted     bytea not null,
    expiry_month      int not null,
    expiry_year       int not null,
    cvv_encrypted     bytea not null,
    type              text not null,
    state             text not null,
    label             text,
    created_at        timestamptz not null default now()
);
create unique index on card (pan_hash);
create index on card (user_id);

create table card_limit (
    card_id       uuid not null references card(id),
    period        text not null,
    amount_minor  bigint not null,
    currency      char(3) not null,
    primary key (card_id, period)
);

create table card_authorization (
    id              uuid primary key,
    card_id         uuid not null references card(id),
    network_ref     text not null unique,
    merchant_name   text not null,
    merchant_mcc    char(4) not null,
    merchant_country char(2) not null,
    amount_minor    bigint not null,
    currency        char(3) not null,
    billing_minor   bigint,
    billing_currency char(3),
    state           text not null,
    decline_reason  text,
    reservation_id  uuid,
    ledger_entry_id uuid,
    authorized_at   timestamptz not null default now(),
    settled_at      timestamptz
);
create index on card_authorization (card_id, authorized_at desc);
