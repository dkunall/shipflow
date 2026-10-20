-- Write-behind statistics (ADR-011). A lifecycle outcome appends one row here inside the scan's transaction
-- (append-only, no hot row); StatsAggregator later claims a batch (DELETE .. RETURNING, SKIP LOCKED), folds it into
-- per-key deltas and applies one UPSERT per key in the same transaction => exactly-once, contention-free scans.
CREATE TABLE delivery_outcome (
    id             bigserial   PRIMARY KEY,
    shipment_id    uuid        NOT NULL,
    outcome        varchar(16) NOT NULL CHECK (outcome IN ('DELIVERED', 'FAILED', 'RTO')),
    carrier_id     bigint,
    origin_hub_id  bigint,
    zone_code      varchar(32) NOT NULL,
    local_day      date        NOT NULL,
    lead_days      integer     CHECK (lead_days IS NULL OR lead_days >= 0),
    on_time        boolean     NOT NULL DEFAULT false,
    first_attempt  boolean     NOT NULL DEFAULT false,
    pincode        varchar(16) NOT NULL,
    phone_hash     varchar(64) NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);
