-- ratekit rating schema, version 1.
-- Safety rules live in constraints, not only in code: if the code is wrong, the database still refuses.

-- One row per account. The balance is prepaid money; the CHECK is the hard stop:
-- no statement can ever leave a balance below zero.
CREATE TABLE accounts (
    id         TEXT PRIMARY KEY,
    balance    NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Tariff versions. A tariff row is never edited: a price change is a new row with a later
-- effective_from, so an old event can always be re-rated with the tariff that applied then.
-- params holds the model-specific numbers (rate, tiers, free quota) as JSON.
CREATE TABLE tariffs (
    id             BIGSERIAL PRIMARY KEY,
    meter          TEXT        NOT NULL,
    model          TEXT        NOT NULL CHECK (model IN ('FLAT', 'TIERED', 'FREE_QUOTA_THEN_FLAT')),
    effective_from TIMESTAMPTZ NOT NULL,
    params         JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (meter, effective_from)
);

-- Idempotency ledger. The primary key is the guard: inserting the same (account, event) twice
-- fails, so a redelivered Kafka message can be detected and skipped.
CREATE TABLE processed_events (
    account_id   TEXT        NOT NULL REFERENCES accounts (id),
    event_id     TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, event_id)
);

-- One charge per processed event: the priced result that billing later sums into invoices.
-- The foreign key means a charge cannot exist for an event that was never recorded as processed.
CREATE TABLE charges (
    id          BIGSERIAL PRIMARY KEY,
    account_id  TEXT           NOT NULL,
    event_id    TEXT           NOT NULL,
    meter       TEXT           NOT NULL,
    quantity    BIGINT         NOT NULL CHECK (quantity > 0),
    amount      NUMERIC(19, 4) NOT NULL CHECK (amount >= 0),
    tariff_id   BIGINT         NOT NULL REFERENCES tariffs (id),
    occurred_at TIMESTAMPTZ    NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (account_id, event_id),
    FOREIGN KEY (account_id, event_id) REFERENCES processed_events (account_id, event_id)
);

-- Billing reads charges per account and period.
CREATE INDEX charges_account_occurred_idx ON charges (account_id, occurred_at);
