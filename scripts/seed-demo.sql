-- Demo data for a local run: one prepaid account and one tariff.
-- 'sms' gives 100 free units per month, then charges 0.05 per unit.
INSERT INTO accounts (id, balance) VALUES ('acc-demo', 100) ON CONFLICT DO NOTHING;

INSERT INTO tariffs (meter, model, effective_from, params) VALUES
    ('sms', 'FREE_QUOTA_THEN_FLAT', '2026-01-01T00:00:00Z', '{"freeUnits":100,"rate":"0.05"}')
ON CONFLICT DO NOTHING;
