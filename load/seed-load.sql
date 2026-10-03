-- Data for the load test: 200 prepaid accounts with a very large balance, and one flat tariff.
-- Spreading events over many accounts lets Kafka distribute them over all partitions.
TRUNCATE invoice_lines, invoices, rejected_events, charges, processed_events, tariffs, accounts CASCADE;

INSERT INTO accounts (id, balance)
SELECT 'load-acc-' || g, 1000000000 FROM generate_series(0, 199) AS g;

INSERT INTO tariffs (meter, model, effective_from, params)
VALUES ('sms-load', 'FLAT', '2026-01-01T00:00:00Z', '{"rate":"0.0001"}');
