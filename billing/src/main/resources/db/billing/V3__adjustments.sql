-- Late charges are billed as adjustment lines on a later invoice (ADR 0020). An issued invoice is
-- never rewritten, so each charge records which invoice billed it, and an invoice run claims the
-- charges it bills by setting that column: a charge can be claimed once, so it is billed exactly once.
ALTER TABLE charges ADD COLUMN invoice_id BIGINT REFERENCES invoices (id);

-- Months whose invoice run has completed. Only an unbilled charge of such a month is late; one of a
-- month never run waits for that month's own run.
CREATE TABLE invoiced_periods (
    period_start TIMESTAMPTZ PRIMARY KEY,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One line per original month and meter of the late usage an invoice bills.
CREATE TABLE invoice_adjustments (
    invoice_id            BIGINT         NOT NULL REFERENCES invoices (id),
    original_period_start TIMESTAMPTZ    NOT NULL,
    meter                 TEXT           NOT NULL,
    quantity              BIGINT         NOT NULL CHECK (quantity > 0),
    amount                NUMERIC(19, 4) NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (invoice_id, original_period_start, meter)
);

-- Invoices written before this migration: their months count as run, and a charge counts as billed
-- by the invoice of its account and month if it was received before that invoice was written. One
-- received later was the "late charge" ADR 0005 left unbilled; it is now billed on the next invoice.
INSERT INTO invoiced_periods (period_start) SELECT DISTINCT period_start FROM invoices;
UPDATE charges c SET invoice_id = i.id
FROM invoices i
WHERE i.account_id = c.account_id AND c.occurred_at >= i.period_start AND c.occurred_at < i.period_end
  AND c.received_at <= i.created_at;

-- An invoice run looks for an account's unbilled charges.
CREATE INDEX charges_unbilled_idx ON charges (account_id, occurred_at) WHERE invoice_id IS NULL;
