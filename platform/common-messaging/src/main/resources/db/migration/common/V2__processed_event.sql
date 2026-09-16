-- Idempotent-consumer dedup table. The primary key IS the dedup key: a second delivery of the
-- same event to the same consumer group fails on insert rather than needing a separate
-- existence check first - see IdempotentHandler.
CREATE TABLE processed_event (
    consumer_group VARCHAR(255) NOT NULL,
    event_id       UUID         NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);
