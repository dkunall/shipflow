-- Transactional outbox doubling as the merchant-webhook delivery queue (one endpoint per merchant).
CREATE TABLE outbox_event (
    id               uuid          PRIMARY KEY,           -- also the event id consumers dedupe on
    merchant_id      uuid          NOT NULL REFERENCES merchant (id),
    aggregate_id     uuid          NOT NULL,
    event_type       varchar(64)   NOT NULL,
    payload          jsonb         NOT NULL,
    status           varchar(12)   NOT NULL DEFAULT 'PENDING',
    attempts         integer       NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz   NOT NULL,
    claim_token      uuid,
    last_error       varchar(500),
    created_at       timestamptz   NOT NULL,
    delivered_at     timestamptz,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','DELIVERED','DEAD')),
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0)
);
-- dispatcher hot query: due PENDING events only
CREATE INDEX ix_outbox_due ON outbox_event (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_aggregate ON outbox_event (aggregate_id, created_at);

CREATE TABLE webhook_attempt (
    id            bigserial    PRIMARY KEY,
    event_id      uuid         NOT NULL REFERENCES outbox_event (id) ON DELETE CASCADE,
    attempt_no    integer      NOT NULL,
    claim_token   uuid         NOT NULL,
    http_status   integer,
    error         varchar(500),
    duration_ms   integer      NOT NULL,
    attempted_at  timestamptz  NOT NULL
);
CREATE INDEX ix_webhook_attempt_event ON webhook_attempt (event_id, attempt_no);

CREATE TABLE webhook_dead_letter (
    id           bigserial    PRIMARY KEY,
    event_id     uuid         NOT NULL UNIQUE REFERENCES outbox_event (id),
    merchant_id  uuid         NOT NULL REFERENCES merchant (id),
    event_type   varchar(64)  NOT NULL,
    attempts     integer      NOT NULL,
    last_error   varchar(500),
    dead_at      timestamptz  NOT NULL,
    replayed_at  timestamptz
);
CREATE INDEX ix_dead_letter_open ON webhook_dead_letter (dead_at) WHERE replayed_at IS NULL;
