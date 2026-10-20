-- Incrementally maintained statistics (UPSERT += on each outcome; never a full recompute).

-- Rolling carrier scorecard: per carrier/zone/day buckets; scoring reads the last N days (<= N rows per carrier).
CREATE TABLE carrier_scorecard_daily (
    carrier_id     bigint      NOT NULL REFERENCES carrier (id),
    zone_code      varchar(32) NOT NULL REFERENCES zone (code),
    day            date        NOT NULL,
    delivered      integer     NOT NULL DEFAULT 0,
    on_time        integer     NOT NULL DEFAULT 0,
    first_attempt  integer     NOT NULL DEFAULT 0,
    rto            integer     NOT NULL DEFAULT 0,
    PRIMARY KEY (carrier_id, zone_code, day),
    CONSTRAINT chk_scorecard CHECK (delivered >= 0 AND rto >= 0 AND on_time BETWEEN 0 AND delivered
                                    AND first_attempt BETWEEN 0 AND delivered)
);
CREATE INDEX ix_scorecard_zone_day ON carrier_scorecard_daily (zone_code, day);

-- Lead-time histogram per lane (carrier, origin hub, destination zone): business days dispatch -> delivered.
CREATE TABLE lane_lead_time (
    carrier_id     bigint      NOT NULL REFERENCES carrier (id),
    origin_hub_id  bigint      NOT NULL REFERENCES hub (id),
    zone_code      varchar(32) NOT NULL REFERENCES zone (code),
    lead_days      integer     NOT NULL,
    samples        integer     NOT NULL,
    PRIMARY KEY (carrier_id, origin_hub_id, zone_code, lead_days),
    CONSTRAINT chk_lane CHECK (lead_days >= 0 AND samples > 0)
);

-- Delivery-risk inputs (customer keyed by SHA-256 of normalised phone; no raw PII in stats).
CREATE TABLE pincode_delivery_stats (
    pincode   varchar(16) PRIMARY KEY REFERENCES pincode (code),
    attempts  integer     NOT NULL DEFAULT 0,
    failures  integer     NOT NULL DEFAULT 0,
    CONSTRAINT chk_pincode_stats CHECK (attempts >= 0 AND failures BETWEEN 0 AND attempts)
);

CREATE TABLE customer_delivery_stats (
    phone_hash  varchar(64) PRIMARY KEY,
    attempts    integer     NOT NULL DEFAULT 0,
    failures    integer     NOT NULL DEFAULT 0,
    rto         integer     NOT NULL DEFAULT 0,
    CONSTRAINT chk_customer_stats CHECK (attempts >= 0 AND failures BETWEEN 0 AND attempts AND rto >= 0)
);
