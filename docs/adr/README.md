# Architecture decision records

An ADR records one decision: the context, what was decided, the alternatives and the consequences. Numbers are sequential and never reused; a decision that changes gets a new ADR that supersedes the old one, and the old one is kept.

| No | Decision | Status |
|---|---|---|
| [0001](0001-money-and-rounding.md) | Money is `BigDecimal` with scale 4 and half-even rounding; unit prices keep full precision and a charge is rounded once | accepted |
| [0002](0002-balance-deduction.md) | The balance is deducted with one atomic `UPDATE ... WHERE balance >= x`; an unaffordable event is rejected and recorded | accepted |
| [0003](0003-package-structure.md) | Services are split into layers; pricing rules live in a framework-free `domain` package | accepted |
| [0004](0004-retry-and-dead-letter.md) | Transient failures are retried with backoff; permanent ones go to the dead-letter topic at once | accepted |
| [0005](0005-invoicing.md) | Invoicing is a plain scheduled run, one invoice per account and month, safe to repeat | accepted |
| [0006](0006-metrics.md) | Metrics through Micrometer in Prometheus format; no collector in the repository | accepted |
| [0007](0007-event-time-window.md) | ingest accepts `occurredAt` from the start of the open month (plus a one-hour grace after a month ends) to five minutes ahead; anything else gets `422` | accepted |
| [0008](0008-accounts-and-top-ups.md) | rating opens accounts and takes top-ups over HTTP; money enters only through an idempotent top-up recorded in a ledger table | accepted |
| [0009](0009-tariff-versions-api.md) | Tariff versions are added over HTTP, checked with rating's own parser, and may not start in the past | accepted |
| [0010](0010-invoice-after-rating-caught-up.md) | billing invoices a month only after rating has committed every usage event written before the late-arrival grace ended | accepted |
| [0011](0011-usage-counter.md) | Units used per account, meter and month are kept in a running counter updated with each charge, instead of summed from charges on every event | accepted |
| [0013](0013-migration-order.md) | rating, like billing, baselines a non-empty schema at version 0, so either service may migrate an empty database first | accepted |
| [0014](0014-retention.md) | Rejected events and their idempotency rows are deleted 90 days after rejection, in batches; rated events stay with their charges | accepted |
| [0015](0015-shared-api-key.md) | When `ratekit.security.api-key` is set, every `/v1` endpoint requires it in `X-Api-Key`; actuator and Swagger stay open | accepted |
