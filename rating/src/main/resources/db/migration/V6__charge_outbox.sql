-- Transactional outbox for the charges billing needs (ADR 0019). Rating writes one row here in the
-- same transaction as the charge, so a charge exists exactly when its outbox row does. A relay then
-- publishes unsent rows to the `charges` Kafka topic in id order and stamps sent_at once Kafka has
-- acknowledged them.
--
-- The row only points at the charge: the charge itself is the payload, and it is never edited.
CREATE TABLE charge_outbox (
    id         BIGSERIAL PRIMARY KEY,
    charge_id  BIGINT      NOT NULL UNIQUE REFERENCES charges (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at    TIMESTAMPTZ
);

-- The relay asks for the oldest unsent rows; sent rows drop out of this index.
CREATE INDEX charge_outbox_unsent_idx ON charge_outbox (id) WHERE sent_at IS NULL;

-- billing gets a database of its own that starts empty: queue every charge stored so far, oldest
-- first, so billing receives the whole history. billing stores each charge once, however often it arrives.
INSERT INTO charge_outbox (charge_id, created_at)
SELECT id, created_at FROM charges ORDER BY id;
