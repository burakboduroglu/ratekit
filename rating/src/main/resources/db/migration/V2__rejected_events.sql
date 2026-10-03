-- Events the prepaid hard stop refused: the account could not afford them.
-- A rejected event is still recorded in processed_events (so a redelivery is skipped, not
-- re-evaluated), and here with what it would have cost, so nothing disappears silently.
CREATE TABLE rejected_events (
    account_id  TEXT           NOT NULL,
    event_id    TEXT           NOT NULL,
    meter       TEXT           NOT NULL,
    quantity    BIGINT         NOT NULL CHECK (quantity > 0),
    amount      NUMERIC(19, 4) NOT NULL CHECK (amount >= 0),
    reason      TEXT           NOT NULL CHECK (reason IN ('INSUFFICIENT_BALANCE')),
    occurred_at TIMESTAMPTZ    NOT NULL,
    rejected_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, event_id),
    FOREIGN KEY (account_id, event_id) REFERENCES processed_events (account_id, event_id)
);
