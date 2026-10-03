-- billing's own tables. billing only READS rating's `charges` table (account_id, meter, quantity,
-- amount, occurred_at); it never writes to it.

-- One invoice per account and billing period. The unique key is what makes an invoice run
-- idempotent: a second attempt for the same account and period inserts nothing.
CREATE TABLE invoices (
    id           BIGSERIAL PRIMARY KEY,
    account_id   TEXT           NOT NULL,
    period_start TIMESTAMPTZ    NOT NULL,
    period_end   TIMESTAMPTZ    NOT NULL,
    total        NUMERIC(19, 4) NOT NULL CHECK (total >= 0),
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CHECK (period_end > period_start),
    UNIQUE (account_id, period_start)
);

-- One line per meter used in the period.
CREATE TABLE invoice_lines (
    invoice_id BIGINT         NOT NULL REFERENCES invoices (id),
    meter      TEXT           NOT NULL,
    quantity   BIGINT         NOT NULL CHECK (quantity > 0),
    amount     NUMERIC(19, 4) NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (invoice_id, meter)
);
