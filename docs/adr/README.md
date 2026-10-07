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
