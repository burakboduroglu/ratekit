# 0014. Retention for idempotency and rejection records

Status: accepted (2026-10-07)

## Context

Every event rating handles leaves a row in `processed_events`, the idempotency guard (ADR 0002, `V1__init.sql`). A rated event also leaves a charge, a refused one a row in `rejected_events`. Nothing was ever deleted. The constraints:

- `charges` and `rejected_events` both have a foreign key to `processed_events`, so a guard row cannot go while either still points at it.
- `charges` is the accounting record: billing sums it into invoices, and a customer's invoice must stay explainable line by line. It is kept.
- The guard must outlive every way an event can come back. If a refused event's guard is gone and the event arrives again, rating evaluates it from scratch and, if the account has been topped up since, **charges** it. An event can come back through Kafka redelivery (seconds to minutes), a manual replay or offset reset (bounded by the topic's retention, 7 days by default) and a client retrying through ingest, which accepts a month's usage until one hour after the month ends (ADR 0007), so up to about 32 days after the usage.

## Decision

- **Only refused events are pruned.** A daily job deletes rejected events older than `rejected-max-age` (default **90 days**) together with their `processed_events` rows.
- **Rated events are kept**, guard row included. Their guard row is tied to the charge by the foreign key, and keeping both is what makes a charge provably unique. `processed_events` therefore grows exactly as fast as `charges`, which is kept anyway; it stops being an unbounded side table.
- **Safe floor.** The age is refused at start-up below 35 days (the longest month plus ingest's grace plus a margin), so a misconfiguration cannot re-open the double-charge window. 90 days is well above it and leaves a quarter of history for support questions ("why was this refused?").
- **Bounded batches.** One statement deletes up to `batch-size` (default 1000) rejections and their guard rows with data-modifying CTEs; the foreign key is checked at the end of the statement, when both rows are gone. Each batch commits on its own; the job repeats until a batch comes back short. A new index on `rejected_events (rejected_at)` (V5) keeps each batch from scanning the table.
- **On by default**, daily at 03:30 UTC (`ratekit.rating.retention.*`), after billing's 02:00 run. A counter `ratekit.retention.deleted{table}` shows what each run removed.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| Also prune guard rows of rated events and drop the foreign key from `charges` | `charges` has its own `UNIQUE (account_id, event_id)`, so it could serve as the guard. But the saving is small (the charge row stays), and the foreign key is what proves no charge exists without a processed event. Revisit if charges are ever archived; then archive both together. |
| Prune by `processed_at` instead of `rejected_at` | The same instant for a rejection (both written in one transaction); `rejected_at` is the column the rejection table is about and the one support asks for. |
| Off by default | The tables would keep growing on every installation that never sets it. The floor makes the default safe to ship on. |
| Table partitioning by month and dropping old partitions | Cheaper deletes at very large volumes, but needs partition management and changes primary keys. Not needed at this size. |

## Consequences

- A refused event can come back and be charged only if it returns more than 90 days after its rejection, which none of the paths above allows unless Kafka retention is raised above that; anyone raising it must raise `rejected-max-age` too.
- `processed_events` and `charges` still grow with usage. Archiving the accounting record is a separate, later decision.
- The cleanup takes row locks on old rows only; rating's live traffic writes new rows, so they do not contend.
