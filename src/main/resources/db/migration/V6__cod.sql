CREATE TABLE cod_remittance (
    id              bigserial     PRIMARY KEY,
    carrier_id      bigint        NOT NULL REFERENCES carrier (id),
    remittance_ref  varchar(100)  NOT NULL,
    currency        varchar(3)    NOT NULL,
    total_amount    numeric(14,2) NOT NULL,
    received_at     timestamptz   NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_remittance UNIQUE (carrier_id, remittance_ref),
    CONSTRAINT chk_remittance_currency CHECK (currency IN ('AED','SAR')),
    CONSTRAINT chk_remittance_total CHECK (total_amount >= 0)
);
CREATE INDEX ix_remittance_carrier_received ON cod_remittance (carrier_id, received_at);

CREATE TABLE cod_remittance_line (
    id               bigserial     PRIMARY KEY,
    remittance_id    bigint        NOT NULL REFERENCES cod_remittance (id) ON DELETE CASCADE,
    tracking_number  varchar(20)   NOT NULL,
    amount           numeric(12,2) NOT NULL,
    CONSTRAINT uq_remittance_line UNIQUE (remittance_id, tracking_number),
    CONSTRAINT chk_remittance_line_amount CHECK (amount > 0)
);
CREATE INDEX ix_remittance_line_tracking ON cod_remittance_line (tracking_number);

-- One run row per (carrier, period); re-running recomputes the result set atomically (idempotent).
CREATE TABLE cod_reconciliation_run (
    id              bigserial     PRIMARY KEY,
    carrier_id      bigint        NOT NULL REFERENCES carrier (id),
    period_from     timestamptz   NOT NULL,
    period_to       timestamptz   NOT NULL,
    run_count       integer       NOT NULL DEFAULT 1,
    matched         integer       NOT NULL DEFAULT 0,
    short           integer       NOT NULL DEFAULT 0,
    excess          integer       NOT NULL DEFAULT 0,
    missing         integer       NOT NULL DEFAULT 0,
    expected_total  numeric(14,2) NOT NULL DEFAULT 0,
    remitted_total  numeric(14,2) NOT NULL DEFAULT 0,
    last_run_at     timestamptz   NOT NULL,
    CONSTRAINT uq_recon_period UNIQUE (carrier_id, period_from, period_to),
    CONSTRAINT chk_recon_period CHECK (period_to > period_from)
);

CREATE TABLE cod_reconciliation_result (
    run_id           bigint        NOT NULL REFERENCES cod_reconciliation_run (id) ON DELETE CASCADE,
    tracking_number  varchar(20)   NOT NULL,
    currency         varchar(3)    NOT NULL,
    shipment_id      uuid          REFERENCES shipment (id),
    expected         numeric(12,2) NOT NULL,
    remitted         numeric(12,2) NOT NULL,
    difference       numeric(12,2) NOT NULL,      -- remitted - expected
    outcome          varchar(8)    NOT NULL,
    PRIMARY KEY (run_id, tracking_number, currency),
    CONSTRAINT chk_recon_outcome CHECK (outcome IN ('MATCHED','SHORT','EXCESS','MISSING')),
    CONSTRAINT chk_recon_diff CHECK (difference = remitted - expected)
);
