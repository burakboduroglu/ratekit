-- billing has a database of its own (ADR 0019). It no longer reads rating's tables: rating sends every
-- charge over the `charges` Kafka topic and billing keeps its own copy here.

-- One row per charge. The primary key is the idempotency guard: Kafka may deliver a charge twice
-- (at-least-once), and the second insert does nothing.
CREATE TABLE charges (
    account_id  TEXT           NOT NULL,
    event_id    TEXT           NOT NULL,
    meter       TEXT           NOT NULL,
    quantity    BIGINT         NOT NULL CHECK (quantity > 0),
    amount      NUMERIC(19, 4) NOT NULL CHECK (amount >= 0),
    occurred_at TIMESTAMPTZ    NOT NULL,
    rated_at    TIMESTAMPTZ    NOT NULL,
    received_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, event_id)
);

-- An invoice run reads one month's charges, account by account.
CREATE INDEX charges_occurred_account_idx ON charges (occurred_at, account_id);

-- The newest progress marker seen on each partition of the `charges` topic: every charge rating
-- committed before published_through is already in the table above, because on that partition it came
-- before the marker. An invoice run waits until every partition's marker is newer than the moment
-- rating was found caught up.
CREATE TABLE charge_feed_watermarks (
    kafka_partition   INT         PRIMARY KEY CHECK (kafka_partition >= 0),
    partition_count   INT         NOT NULL CHECK (partition_count > 0),
    published_through TIMESTAMPTZ NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
