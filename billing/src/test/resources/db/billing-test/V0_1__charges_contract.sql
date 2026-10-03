-- Test only. In a real deployment rating's migrations create `charges`. This is the subset of
-- columns billing reads: if rating changes any of them, billing breaks, so they are a contract.
CREATE TABLE charges (
    id          BIGSERIAL PRIMARY KEY,
    account_id  TEXT           NOT NULL,
    event_id    TEXT           NOT NULL,
    meter       TEXT           NOT NULL,
    quantity    BIGINT         NOT NULL,
    amount      NUMERIC(19, 4) NOT NULL,
    occurred_at TIMESTAMPTZ    NOT NULL
);
