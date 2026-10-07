-- The retention cleanup finds old rejections by when they were rejected (ADR 0014); without this
-- index every batch would scan the whole table.
CREATE INDEX rejected_events_rejected_at_idx ON rejected_events (rejected_at);
