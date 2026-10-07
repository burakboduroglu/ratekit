-- Units each account used of each meter in each billing period (calendar month, UTC).
-- Rating reads this before pricing an event (free quota and tiers depend on it) and adds to it in the
-- same transaction that stores the charge, so it always equals the sum of that period's charges.
-- One row lookup replaces a SUM over the whole month's charges on every event.
CREATE TABLE usage_counters (
    account_id   TEXT        NOT NULL REFERENCES accounts (id),
    meter        TEXT        NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    units        BIGINT      NOT NULL CHECK (units >= 0),
    PRIMARY KEY (account_id, meter, period_start)
);

-- Charges stored before this migration count too. The month is truncated in UTC, like BillingPeriod.
INSERT INTO usage_counters (account_id, meter, period_start, units)
SELECT account_id,
       meter,
       date_trunc('month', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC',
       SUM(quantity)
FROM charges
GROUP BY 1, 2, 3;
