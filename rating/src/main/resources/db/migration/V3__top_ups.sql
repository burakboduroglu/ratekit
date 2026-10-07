-- Every credit to a balance, one row per top-up. Money enters an account only through this table,
-- so a balance can always be explained by its top-ups minus its charges.
-- The primary key is the idempotency guard: a client that retries a top-up with the same id cannot
-- credit the account twice.
CREATE TABLE top_ups (
    account_id TEXT           NOT NULL REFERENCES accounts (id),
    top_up_id  TEXT           NOT NULL,
    amount     NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, top_up_id)
);
