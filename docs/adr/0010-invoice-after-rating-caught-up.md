# 0010. Invoice a month only after rating has caught up

Status: accepted (2026-10-07)

## Context

ADR 0007 stops ingest from accepting usage for a month one hour after it ends, and billing's scheduler ran at 02:00 on the 1st. That left one hole: an event accepted at 00:59 sits in Kafka until rating reaches it. Under load the consumer lag was measured at up to 11,547 records (docs/perf.md); if it has not drained by the time billing runs, the event is charged to the balance after its month was invoiced, and it never appears on an invoice.

## Decision

- Before an invoice run billing checks two things, and refuses with `409` if either fails:
  1. The late-arrival grace is over: now is after `period end + late-arrival-grace` (1 hour, which must equal ingest's setting).
  2. Rating has caught up to that cutoff: for each partition of `usage-events`, the committed offset of rating's consumer group is at or past the first record whose Kafka timestamp is at or after the cutoff (or past the end of the partition if nothing was written since).
- Kafka is asked through its admin API (`listOffsets` with `OffsetSpec.forTimestamp`, `listConsumerGroupOffsets`). Billing never consumes the topic.
- If Kafka cannot be asked, the answer is `503`: an unknown state is not taken as "caught up".
- The scheduler now fires hourly on the 1st from 02:00 UTC. A run that finds rating behind is skipped with a warning; once the month is invoiced, further runs create nothing (ADR 0005 made runs idempotent, which is what makes retrying by schedule safe).
- `ratekit.billing.rating-progress.enabled=false` turns the check off for running billing without Kafka.

## Why not the alternatives

| Option | Verdict |
| --- | --- |
| Total consumer lag is zero | Never true under steady traffic, so the month would never be invoiced. Only the records before the cutoff matter. |
| A fixed delay (run on the 2nd) | Usually enough, never proven; a long outage still loses usage silently. |
| Rating publishes a "period closed" event | Cleaner long-term, but rating would need to know when ingest stops accepting, and billing would need a consumer. More moving parts for the same answer. |

## Consequences

- Rating commits an offset only after the database transaction of the records before it, so "committed" means charged, rejected or dead-lettered. Uncommitted but processed records make the check conservative, never wrong.
- The cutoff is compared with record timestamps set by ingest's producer (`CreateTime`). With several ingest instances whose clocks differ, a record written just before the cutoff could carry a timestamp after it and not be waited for; NTP keeps that window to milliseconds. `LogAppendTime` on the topic would remove it.
- billing now depends on Kafka being reachable for invoice runs (not for reading invoices).
- Amended 2026-10-08: with billing on its own database (ADR 0019), "rated" is not enough: the charges must also have reached billing's table. A third check waits for a progress marker from rating's outbox relay on every partition of `charges`, newer than the moment this check passed.
- The grace is configured twice (ingest and billing) and must match. A wider grace in ingest than in billing means billing does not wait for the extra late usage; the README demo relies on that and simply waits two seconds.
