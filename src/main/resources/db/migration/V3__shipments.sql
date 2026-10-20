CREATE TABLE shipment (
    id                   uuid          PRIMARY KEY,
    merchant_id          uuid          NOT NULL REFERENCES merchant (id),
    merchant_order_ref   varchar(100)  NOT NULL,
    tracking_number      varchar(20)   NOT NULL UNIQUE,
    source               varchar(32)   NOT NULL,
    status               varchar(24)   NOT NULL,
    status_rank          integer       NOT NULL,
    status_event_at      timestamptz,                -- occurred_at of the last APPLIED scan (null until first scan)
    version              bigint        NOT NULL DEFAULT 0,
    origin_hub_id        bigint        NOT NULL REFERENCES hub (id),
    delivery_hub_id      bigint        NOT NULL REFERENCES hub (id),
    destination_pincode  varchar(16)   NOT NULL REFERENCES pincode (code),
    zone_code            varchar(32)   NOT NULL REFERENCES zone (code),
    country_code         varchar(2)    NOT NULL REFERENCES country (code),
    consignee_name       varchar(200)  NOT NULL,
    consignee_phone      varchar(20)   NOT NULL,
    consignee_phone_hash varchar(64)   NOT NULL,
    address_line1        varchar(300)  NOT NULL,
    address_line2        varchar(300),
    landmark             varchar(200),
    city                 varchar(100)  NOT NULL,
    dest_lat             double precision NOT NULL,
    dest_lng             double precision NOT NULL,
    weight_g             integer       NOT NULL,
    payment_mode         varchar(8)    NOT NULL,
    cod_amount           numeric(12,2),
    currency             varchar(3)    NOT NULL,
    declared_value       numeric(12,2) NOT NULL,
    carrier_id           bigint        REFERENCES carrier (id),
    carrier_awb          varchar(64),
    shipping_cost        numeric(12,2),
    dispatch_date        date,
    promised_date        date,
    edd_explanation      jsonb,
    risk_score           integer       NOT NULL DEFAULT 0,
    risk_band            varchar(8)    NOT NULL DEFAULT 'LOW',
    risk_assessment      jsonb,
    attempt_count        integer       NOT NULL DEFAULT 0,
    last_ndr_reason      varchar(32),
    delivered_at         timestamptz,
    created_at           timestamptz   NOT NULL,
    updated_at           timestamptz   NOT NULL,
    CONSTRAINT uq_shipment_merchant_order UNIQUE (merchant_id, merchant_order_ref),
    CONSTRAINT chk_shipment_status CHECK (status IN ('CREATED','ALLOCATED','PICKED_UP','IN_TRANSIT','AT_HUB',
        'OUT_FOR_DELIVERY','DELIVERED','DELIVERY_FAILED','RTO_INITIATED','RTO_IN_TRANSIT','RTO_DELIVERED','CANCELLED')),
    CONSTRAINT chk_shipment_payment CHECK ((payment_mode = 'COD' AND cod_amount > 0)
                                        OR (payment_mode = 'PREPAID' AND cod_amount IS NULL)),
    CONSTRAINT chk_shipment_currency CHECK (currency IN ('AED','SAR')),
    CONSTRAINT chk_shipment_weight CHECK (weight_g > 0),
    CONSTRAINT chk_shipment_values CHECK (declared_value >= 0),
    CONSTRAINT chk_shipment_risk CHECK (risk_score BETWEEN 0 AND 100 AND risk_band IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT chk_shipment_attempts CHECK (attempt_count >= 0),
    CONSTRAINT chk_shipment_allocated_has_carrier CHECK (status IN ('CREATED','CANCELLED') OR carrier_id IS NOT NULL),
    CONSTRAINT chk_shipment_delivered_at CHECK (status <> 'DELIVERED' OR delivered_at IS NOT NULL)
);
-- merchant list (default sort) and status-filtered list
CREATE INDEX ix_shipment_merchant_created ON shipment (merchant_id, created_at DESC, id);
CREATE INDEX ix_shipment_merchant_status_created ON shipment (merchant_id, status, created_at DESC);
-- partial indexes for workers / ops queries that only care about a small live subset
CREATE INDEX ix_shipment_ofd_by_hub ON shipment (delivery_hub_id) WHERE status = 'OUT_FOR_DELIVERY';
CREATE INDEX ix_shipment_ndr_open ON shipment (updated_at) WHERE status = 'DELIVERY_FAILED';
CREATE INDEX ix_shipment_allocated ON shipment (updated_at) WHERE status = 'ALLOCATED';
CREATE INDEX ix_shipment_cod_delivered ON shipment (carrier_id, delivered_at)
    WHERE payment_mode = 'COD' AND status = 'DELIVERED';
CREATE INDEX ix_shipment_awb ON shipment (carrier_id, carrier_awb) WHERE carrier_awb IS NOT NULL;

-- Append-only audit trail of every status transition (including inferred gap-fill steps).
CREATE TABLE shipment_status_history (
    id             bigserial    PRIMARY KEY,
    shipment_id    uuid         NOT NULL REFERENCES shipment (id),
    from_status    varchar(24),
    to_status      varchar(24)  NOT NULL,
    reason         varchar(300),
    actor          varchar(100) NOT NULL,
    inferred       boolean      NOT NULL DEFAULT false,
    location       varchar(200),
    occurred_at    timestamptz  NOT NULL,
    recorded_at    timestamptz  NOT NULL DEFAULT now(),
    correlation_id varchar(64)
);
CREATE INDEX ix_history_shipment ON shipment_status_history (shipment_id, id);

-- Explainable allocation decisions; one RESERVED (capacity-holding) decision per shipment at most.
CREATE TABLE allocation_decision (
    id              bigserial     PRIMARY KEY,
    shipment_id     uuid          NOT NULL REFERENCES shipment (id),
    carrier_id      bigint        REFERENCES carrier (id),
    zone_code       varchar(32)   NOT NULL,
    service_date    date          NOT NULL,
    outcome         varchar(16)   NOT NULL,
    score           numeric(10,6),
    explanation     jsonb         NOT NULL,
    release_reason  varchar(200),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    released_at     timestamptz,
    CONSTRAINT chk_alloc_outcome CHECK (outcome IN ('RESERVED','RELEASED','UNALLOCATED')),
    CONSTRAINT chk_alloc_carrier CHECK (outcome = 'UNALLOCATED' OR carrier_id IS NOT NULL)
);
CREATE UNIQUE INDEX ux_alloc_one_reserved ON allocation_decision (shipment_id) WHERE outcome = 'RESERVED';
CREATE INDEX ix_alloc_shipment ON allocation_decision (shipment_id, id);

-- Carrier booking with an idempotent, deterministic booking_ref (sent to the carrier as its idempotency key).
CREATE TABLE carrier_booking (
    id            bigserial    PRIMARY KEY,
    shipment_id   uuid         NOT NULL REFERENCES shipment (id),
    carrier_id    bigint       NOT NULL REFERENCES carrier (id),
    booking_ref   varchar(80)  NOT NULL UNIQUE,
    status        varchar(16)  NOT NULL,
    awb           varchar(64),
    attempts      integer      NOT NULL DEFAULT 0,
    lease_until   timestamptz,
    last_error    varchar(500),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_booking_shipment_carrier UNIQUE (shipment_id, carrier_id),
    CONSTRAINT chk_booking_status CHECK (status IN ('PENDING','CONFIRMED','FAILED','UNKNOWN','CANCELLED')),
    CONSTRAINT chk_booking_awb CHECK (status <> 'CONFIRMED' OR awb IS NOT NULL)
);
CREATE UNIQUE INDEX ux_booking_one_active ON carrier_booking (shipment_id) WHERE status IN ('PENDING','CONFIRMED','UNKNOWN');
CREATE INDEX ix_booking_uncertain ON carrier_booking (updated_at) WHERE status IN ('PENDING','UNKNOWN');
