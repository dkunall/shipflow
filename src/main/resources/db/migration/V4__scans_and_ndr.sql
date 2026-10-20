-- Every received scan is stored (also duplicates of state, late/out-of-order events); dedupe by carrier event id.
CREATE TABLE scan_event (
    id                bigserial    PRIMARY KEY,
    shipment_id       uuid         NOT NULL REFERENCES shipment (id),
    carrier_id        bigint       NOT NULL REFERENCES carrier (id),
    carrier_event_id  varchar(100) NOT NULL,
    event_code        varchar(24)  NOT NULL,
    occurred_at       timestamptz  NOT NULL,
    received_at       timestamptz  NOT NULL,
    location          varchar(200),
    ndr_reason        varchar(32),
    source            varchar(16)  NOT NULL,
    outcome           varchar(16)  NOT NULL,
    detail            varchar(300),
    CONSTRAINT uq_scan_carrier_event UNIQUE (carrier_id, carrier_event_id),
    CONSTRAINT chk_scan_outcome CHECK (outcome IN ('APPLIED','OUT_OF_ORDER','STALE','REJECTED')),
    CONSTRAINT chk_scan_source CHECK (source IN ('RIDER_APP','CARRIER_WEBHOOK','OPS')),
    CONSTRAINT chk_scan_ndr CHECK (event_code <> 'DELIVERY_FAILED' OR ndr_reason IS NOT NULL)
);
CREATE INDEX ix_scan_shipment ON scan_event (shipment_id, occurred_at, id);

-- One row per last-mile attempt (failed or successful).
CREATE TABLE delivery_attempt (
    shipment_id  uuid         NOT NULL REFERENCES shipment (id),
    attempt_no   integer      NOT NULL,
    outcome      varchar(16)  NOT NULL,
    ndr_reason   varchar(32),
    action       varchar(16)  NOT NULL,
    carrier_id   bigint       NOT NULL REFERENCES carrier (id),
    occurred_at  timestamptz  NOT NULL,
    PRIMARY KEY (shipment_id, attempt_no),
    CONSTRAINT chk_attempt_no CHECK (attempt_no > 0),
    CONSTRAINT chk_attempt_outcome CHECK (outcome IN ('DELIVERED','FAILED')),
    CONSTRAINT chk_attempt_action CHECK (action IN ('NONE','REATTEMPT','RTO')),
    CONSTRAINT chk_attempt_reason CHECK (outcome <> 'FAILED' OR ndr_reason IS NOT NULL)
);
CREATE INDEX ix_attempt_failed_time ON delivery_attempt (occurred_at) WHERE outcome = 'FAILED';
