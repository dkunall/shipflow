CREATE TABLE rider (
    id        bigserial    PRIMARY KEY,
    code      varchar(32)  NOT NULL UNIQUE,
    name      varchar(100) NOT NULL,
    hub_id    bigint       NOT NULL REFERENCES hub (id),
    capacity  integer      NOT NULL,
    active    boolean      NOT NULL DEFAULT true,
    CONSTRAINT chk_rider_capacity CHECK (capacity BETWEEN 1 AND 500)
);
CREATE INDEX ix_rider_hub_active ON rider (hub_id) WHERE active;

CREATE TABLE route_plan (
    id                 bigserial     PRIMARY KEY,
    hub_id             bigint        NOT NULL REFERENCES hub (id),
    algorithm          varchar(64)   NOT NULL,
    stops              integer       NOT NULL,
    riders_used        integer       NOT NULL,
    unassigned         integer       NOT NULL,
    optimized_km       numeric(12,3) NOT NULL,
    baseline_km        numeric(12,3) NOT NULL,
    compute_ms         integer       NOT NULL,
    created_by         varchar(100)  NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX ix_route_plan_hub ON route_plan (hub_id, created_at DESC);

CREATE TABLE route_stop (
    plan_id      bigint   NOT NULL REFERENCES route_plan (id) ON DELETE CASCADE,
    rider_id     bigint   NOT NULL REFERENCES rider (id),
    seq          integer  NOT NULL,
    shipment_id  uuid     NOT NULL REFERENCES shipment (id),
    leg_km       numeric(10,3) NOT NULL,
    PRIMARY KEY (plan_id, rider_id, seq),
    CONSTRAINT uq_route_stop_shipment UNIQUE (plan_id, shipment_id),
    CONSTRAINT chk_route_seq CHECK (seq > 0)
);
