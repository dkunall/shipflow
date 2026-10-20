CREATE TABLE idempotency_record (
    id              bigserial    PRIMARY KEY,
    scope           varchar(100) NOT NULL,     -- merchant id (or subject) owning the key
    idem_key        varchar(255) NOT NULL,
    operation       varchar(64)  NOT NULL,
    request_hash    varchar(64)  NOT NULL,
    status          varchar(12)  NOT NULL,
    resource_id     uuid,
    response_status integer,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    CONSTRAINT uq_idempotency UNIQUE (scope, idem_key),
    CONSTRAINT chk_idem_status CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    CONSTRAINT chk_idem_completed CHECK (status <> 'COMPLETED' OR response_status IS NOT NULL)
);
CREATE INDEX ix_idempotency_expiry ON idempotency_record (expires_at);
