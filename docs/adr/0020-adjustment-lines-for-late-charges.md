# 0020. Late charges are billed as adjustment lines on the next invoice

Status: accepted (2026-10-08). Replaces the "Late charges are not billed" consequence of ADR 0005.

## Context

An issued invoice is never rewritten (ADR 0005). ADR 0007 and 0010 keep usage from reaching rating after its month was invoiced along the normal path, but not along every path: a dead letter replayed by hand days later (ADR 0004) is rated with its original `occurredAt` and produces a charge for a month whose invoice is already out. Since ADR 0019 that charge reaches billing's own table over the charge feed, and an invoice rerun skips the account, so the charge was taken from the prepaid balance and never shown to the customer. In telecom terms this is a late CDR, and the usual answer is an adjustment on the next bill.

## Decision

- **Each charge records which invoice billed it.** `charges.invoice_id` (V3) is empty until an invoice claims the charge. An invoice is written in one transaction: insert the header (the unique `(account, period)` key decides who writes it, as before), then claim with one `UPDATE charges SET invoice_id = :invoice WHERE invoice_id IS NULL AND ... RETURNING ...`, summed per month and meter in the database, then write the lines, the adjustments and the total. A second claim of the same charge, even by a concurrent run of another month, waits on the row lock, then finds `invoice_id` set and skips it, so **each charge is billed exactly once**: by the unique key on the invoice and the conditional update on the charge, not by the delivery path (which stays at-least-once).
- **What an invoice for month M bills:** the account's unbilled charges of M as lines, one per meter, and its unbilled charges of an earlier month **whose invoice run has completed** as adjustment lines, one per original month and meter. The total is the sum of both, exact as before (ADR 0001).
- **Which months count as invoiced:** a run that finishes records its month in `invoiced_periods`. Only then are the month's still-unbilled charges late. A charge of a month never run is not swept into a later invoice; it waits for its own month's run. A run that fails part-way records nothing, so its rerun, not the next month, bills the accounts it missed.
- **Which accounts a run for M visits:** those with charges in M (as before, so already invoiced ones are counted as such) and those with late charges. An account whose only news is a late charge gets an invoice for M that holds only adjustments.
- **The API** returns `adjustments: [{originalPeriod, meter, quantity, amount}]` next to `lines`, and `total` includes them.
- Existing data (V3): months that have invoices count as invoiced; a charge counts as billed by its month's invoice if billing received it before that invoice was written, and is late otherwise.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| Rewrite the issued invoice | Breaks ADR 0005: a customer may already have paid or archived it. |
| A separate credit/debit note per late charge | The accounting-grade answer, with its own numbering and document type. More than this project needs; the adjustment line carries the same information on a document that exists anyway. |
| Decide "late" when the charge arrives (an invoice for its month exists) and store a flag | Races with a run in progress: the invoice may be summing while the charge is inserted, and neither sees the other. Claiming in the invoice's transaction has no such window. |
| Bill every unbilled charge of any earlier month | Simpler, but a month that was never run (or whose run failed half-way) would be swallowed into the next month as "adjustments". |

## Consequences

- No charge that reaches billing's table is lost or billed twice; a test replays a September charge twice after September was invoiced and finds it once, as an adjustment, on October's invoice. Another starts October's and November's runs at once against the same late charge, and only one of them bills it.
- A late charge waits for the account's next invoice run, usually the next month's; it is invisible to the customer until then.
- If an account had no invoice for the late charge's month at all, a rerun of that month bills it there as a normal line, and the next month's run bills it as an adjustment. Whichever runs first takes it.
- Running months out of order (M+1 before M) leaves M's charges waiting for M's own run, not lost.
- Invoices get a second line table (`invoice_adjustments`), and every claimed charge is updated once. The claim uses a partial index on unbilled charges.
- Still open: adjustments are only ever positive (charges are never negative). Reversing a wrong charge (a credit) needs a negative adjustment and a rule for totals that go below zero, so it is out of scope.
