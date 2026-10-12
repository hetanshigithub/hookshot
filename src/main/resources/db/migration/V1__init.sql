-- Webhook targets registered by a customer.
CREATE TABLE endpoints (
    id          UUID                     PRIMARY KEY,
    url         VARCHAR(2048)            NOT NULL,
    secret      VARCHAR(128)             NOT NULL,
    active      BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Events published by producers. The unique idempotency key makes publishing safe to retry.
CREATE TABLE events (
    id               UUID                     PRIMARY KEY,
    event_type       VARCHAR(100)             NOT NULL,
    payload          TEXT                     NOT NULL,
    idempotency_key  VARCHAR(255),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_events_idempotency_key UNIQUE (idempotency_key)
);

-- One row per (event, endpoint): this table doubles as the work queue.
-- status: PENDING -> IN_FLIGHT -> SUCCEEDED | FAILED (will retry) | DEAD (gave up)
CREATE TABLE deliveries (
    id                UUID                     PRIMARY KEY,
    endpoint_id       UUID                     NOT NULL REFERENCES endpoints (id),
    event_id          UUID                     NOT NULL REFERENCES events (id),
    status            VARCHAR(20)              NOT NULL,
    attempts          INT                      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_status_code  INT,
    last_error        VARCHAR(500),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_deliveries_event_endpoint UNIQUE (event_id, endpoint_id)
);

-- The worker's hot query: "what is due now?"
CREATE INDEX idx_deliveries_due ON deliveries (status, next_attempt_at);
