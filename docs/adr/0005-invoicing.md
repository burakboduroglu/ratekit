# 0005. Invoicing: a plain scheduled run, one invoice per account and month

Status: accepted (2026-10-03)

## Context

`billing` turns the charges `rating` stored into invoices. The run must be safe to repeat (a crashed or double-started run must not produce duplicate or half-written invoices), must not load every account into memory, and must agree with `rating` on where a month begins and ends.

## Decision

- **Unit of work:** one invoice per account and calendar month (UTC), with one line per meter and a total that is the exact sum of the lines. Charges are already rounded (ADR 0001), so the total is never rounded again.
- **Idempotency by key:** `invoices` has `UNIQUE (account_id, period_start)` and the header is written with `INSERT ... ON CONFLICT DO NOTHING RETURNING id`. A second attempt gets no id back and writes nothing. This also holds for two runs started at the same moment: a test starts four at once and gets each invoice exactly once.
- **One transaction per invoice, not per run:** a failure part-way leaves earlier invoices intact, and a rerun finishes the rest.
- **Batches by account id:** accounts are read in keyset-paged batches (`account_id > last ORDER BY account_id LIMIT n`, default 500), and usage is summed per meter in the database. Memory does not grow with the number of accounts.
- **Only finished months:** a run for a month that has not ended is refused with `409`, because an invoice written early would miss usage still to come.
- **The month is a shared definition:** `BillingPeriod` (calendar month in UTC, start inclusive, end exclusive) lives in `common`, so rating's quota accumulation and billing's invoices cannot drift apart. A usage at 01:00 on 1 October in Turkey is 22:00 on 30 September in UTC and belongs to September.
- **Triggering:** `POST /v1/invoice-runs` runs a month on demand. An optional scheduler (`ratekit.billing.scheduler.enabled`, cron default 02:00 UTC on the 1st) invoices the month that just ended. It is off by default so nothing runs by surprise.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| Spring Batch | Gives chunking, restart and parallel partitions, but is a heavy framework with its own metadata tables. The keyset loop and the unique key already give chunking and restartability. Revisit when one run must be split across several workers. |
| One transaction for the whole run | A single failure would roll back thousands of good invoices. |
| Kafka-driven per-account close | Real-time, but needs a "period closed" signal and more moving parts than a monthly job. |
| Rounding the invoice total once at the end | Total would disagree with the sum of the charges the customer can see. |

## Consequences

- **Late charges are not billed.** An event rated after its month was invoiced produces a charge that no rerun adds, because an issued invoice is never rewritten. A test pins this behaviour. Handling it properly means a credit or adjustment invoice in the next period, and is out of scope.
- **Shared database.** `billing` reads `rating`'s `charges` table directly (account_id, meter, quantity, amount, occurred_at) and owns only `invoices` and `invoice_lines`, with its own Flyway history table. That is simple, but it makes those five columns a contract: renaming one in `rating` breaks `billing`. A database per service with charges delivered by event or API is the next step if the services must be deployed and evolved independently. The integration test creates a minimal `charges` table with exactly those columns to document the contract.
- **The run is synchronous.** `POST /v1/invoice-runs` answers when the run is done. For very large volumes it should become a job that returns at once and reports progress.
- **Zero-total invoices exist.** An account that used only free quota still gets an invoice with a zero total, so the usage is visible.
