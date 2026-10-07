# 0011. A running usage counter instead of summing charges per event

Status: accepted (2026-10-07)

## Context

Free quotas and tiers depend on how much the account already used of the meter this month (`cost(before + quantity) - cost(before)`, see `Rater`). Rating found "before" with `SELECT SUM(quantity) FROM charges WHERE account_id = ? AND meter = ? AND occurred_at` in the month, on every event. The index `charges (account_id, occurred_at)` does not contain `meter`, and even with it the sum reads every charge the account had this month: the cost of rating one event grew through the month, and was highest for the busiest accounts. The load test ran for seconds, so it never showed.

## Decision

- A table `usage_counters (account_id, meter, period_start, units)` with that triple as primary key holds the units charged so far in each calendar month (UTC, `BillingPeriod`).
- Rating reads one row (missing means 0) before pricing, and after storing a charge adds the event's quantity with one statement: `INSERT ... ON CONFLICT (account_id, meter, period_start) DO UPDATE SET units = usage_counters.units + EXCLUDED.units`. Both run in the event's transaction (`RatingService.handle`), so the counter and the charges move together or not at all.
- Only a rated event counts. A rejected event used nothing and a duplicate was counted the first time, exactly as the old sum over `charges` behaved.
- The migration (`V4__usage_counters.sql`) fills the counters from the charges already stored, truncating each charge's `occurred_at` to the month in UTC, so an existing database keeps its quotas.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| Add `meter` to the charges index | Cheaper lookups, but still a sum over a growing set of rows per event. |
| Keep the counter in memory in rating | Lost on restart, wrong with more than one consumer thread or instance, and not in the same transaction as the charge. |
| Derive the counter in billing or a stream processor | Rating needs the number synchronously, inside the transaction that prices the event. |

## Consequences

- Rating an event costs one primary-key lookup and one upsert, independent of how much the account used this month.
- The counter is a second copy of information that `charges` also holds. The invariant (counter = sum of that month's charges) is protected by the shared transaction and pinned by an integration test that mixes rated, rejected and duplicate events across two months; a second test checks the backfill.
- Concurrency is unchanged: one account's events stay on one partition and are rated one after another (ADR 0002 and the Kafka key), so the read of "before" cannot race another event of the same account. The upsert itself is atomic in any case.
- Anyone who deletes or edits charges by hand must fix the counters too. Nothing in the application does.
