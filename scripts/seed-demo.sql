-- Demo tariff for a local run: 'sms' gives 100 free units per month, then charges 0.05 per unit.
-- The demo account is opened and topped up through rating's API (see the README Quick start).

INSERT INTO tariffs (meter, model, effective_from, params) VALUES
    ('sms', 'FREE_QUOTA_THEN_FLAT', '2026-01-01T00:00:00Z', '{"freeUnits":100,"rate":"0.05"}')
ON CONFLICT DO NOTHING;
