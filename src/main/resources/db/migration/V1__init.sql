-- =============================================================================
-- V1__init.sql — Phase 1 initial schema
-- 4 tables: notification, delivery, delivery_attempt, idempotency_record
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Table 1: notification
-- Represents a logical notification event sent to a single recipient.
-- ---------------------------------------------------------------------------
CREATE TABLE notification (
    id           UUID         NOT NULL,
    event_id     VARCHAR(64)  NOT NULL,
    recipient_id VARCHAR(64)  NOT NULL,
    type         VARCHAR(40)  NOT NULL,
    payload      JSONB        NOT NULL,
    read_at      TIMESTAMPTZ  NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_notification          PRIMARY KEY (id),
    CONSTRAINT uq_notification_event    UNIQUE (event_id, recipient_id, type),
    CONSTRAINT ck_notification_type     CHECK (type IN (
        'ENROLLMENT_COMPLETED',
        'PAYMENT_CONFIRMED',
        'COURSE_STARTING_TOMORROW',
        'ENROLLMENT_CANCELLED'
    ))
);

COMMENT ON TABLE notification IS 'Logical notification event per recipient (1차 dedup anchor)';

-- List query: recipient inbox with read/unread filter
CREATE INDEX idx_notification_recipient_read ON notification (recipient_id, read_at);

-- ---------------------------------------------------------------------------
-- Table 2: delivery
-- One row per (notification, channel) — transport-level delivery tracking.
-- ---------------------------------------------------------------------------
CREATE TABLE delivery (
    id              UUID         NOT NULL,
    notification_id UUID         NOT NULL,
    channel         VARCHAR(20)  NOT NULL,
    state           VARCHAR(20)  NOT NULL,
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    last_error      TEXT         NULL,
    sent_at         TIMESTAMPTZ  NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_delivery                       PRIMARY KEY (id),
    CONSTRAINT fk_delivery_notification          FOREIGN KEY (notification_id)
                                                     REFERENCES notification (id)
                                                     ON DELETE CASCADE,
    CONSTRAINT uq_delivery_per_channel           UNIQUE (notification_id, channel),
    CONSTRAINT ck_delivery_channel               CHECK (channel IN ('EMAIL', 'IN_APP')),
    CONSTRAINT ck_delivery_state                 CHECK (state IN ('PENDING', 'SENT', 'DEAD')),
    CONSTRAINT ck_delivery_attempt_count_nonneg  CHECK (attempt_count >= 0),
    CONSTRAINT ck_delivery_sent_at_state_sync    CHECK (
        (state = 'SENT' AND sent_at IS NOT NULL) OR
        (state IN ('PENDING', 'DEAD') AND sent_at IS NULL)
    )
);

COMMENT ON TABLE delivery IS 'Per-channel delivery record; channel is transport identity, not notification identity (1.5차 dedup anchor)';

-- Worker polling index: find PENDING/DEAD rows efficiently
CREATE INDEX idx_delivery_state ON delivery (state);

-- ---------------------------------------------------------------------------
-- Table 3: delivery_attempt
-- Retry/claim queue entry for a single delivery attempt.
-- ---------------------------------------------------------------------------
CREATE TABLE delivery_attempt (
    id              UUID         NOT NULL,
    delivery_id     UUID         NOT NULL,
    state           VARCHAR(20)  NOT NULL,
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    claimed_by      VARCHAR(64)  NULL,
    claimed_until   TIMESTAMPTZ  NULL,
    last_error      TEXT         NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_delivery_attempt                      PRIMARY KEY (id),
    CONSTRAINT fk_delivery_attempt_delivery             FOREIGN KEY (delivery_id)
                                                            REFERENCES delivery (id)
                                                            ON DELETE CASCADE,
    CONSTRAINT ck_delivery_attempt_state                CHECK (state IN ('READY', 'IN_PROGRESS', 'DONE', 'FAILED')),
    CONSTRAINT ck_delivery_attempt_count_nonneg         CHECK (attempt_count >= 0),
    CONSTRAINT ck_delivery_attempt_claim_sync           CHECK (
        (claimed_by IS NULL AND claimed_until IS NULL) OR
        (claimed_by IS NOT NULL AND claimed_until IS NOT NULL)
    )
);

COMMENT ON TABLE delivery_attempt IS 'Retry/claim queue row per attempt; attempt_count starts at 0 for each new row';

-- Partial indexes for worker query patterns
-- findClaimableIds: SKIP LOCKED scan over READY rows ordered by next_attempt_at
CREATE INDEX idx_da_ready       ON delivery_attempt (next_attempt_at)  WHERE state = 'READY';
-- Reaper: find expired IN_PROGRESS claims by claimed_until
CREATE INDEX idx_da_in_progress ON delivery_attempt (claimed_until)    WHERE state = 'IN_PROGRESS';
-- Cleanup worker: find terminal rows by age
CREATE INDEX idx_da_terminal    ON delivery_attempt (updated_at)       WHERE state IN ('DONE', 'FAILED');
-- General: list all attempts for a given delivery
CREATE INDEX idx_da_by_delivery ON delivery_attempt (delivery_id);

-- ---------------------------------------------------------------------------
-- Table 4: idempotency_record
-- 2차 dedup via Idempotency-Key request header; module-independent (raw UUID target_id).
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_record (
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    target_id       UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_idempotency_record PRIMARY KEY (idempotency_key)
);

COMMENT ON TABLE idempotency_record IS '2차 dedup: optional Idempotency-Key header; target_id is a raw UUID, module-independent';

-- Cleanup worker: scan expired records
CREATE INDEX idx_idempotency_expires ON idempotency_record (expires_at);
