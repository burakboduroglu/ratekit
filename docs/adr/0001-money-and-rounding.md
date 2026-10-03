# 0001. Money and rounding

Status: accepted (2026-10-03)

## Context

ratekit prices usage events, deducts prepaid balance and sums charges into invoices. These three steps must agree to the last digit. `double` cannot represent most decimal fractions exactly (0.1 + 0.2 != 0.3), and different rounding in different services would make an invoice differ from the sum of its charges.

## Decision

- Amounts are `BigDecimal`, wrapped in the `Money` record in `common`. No `double` or `float` anywhere near money.
- Every `Money` has scale 4 and rounding mode `HALF_EVEN` (banker's rounding). Both constants live only in `Money`.
- Rounding happens where a value is produced: construction and `times`. `plus` and `minus` operate on already-rounded values, so they are exact.
- A charge is rounded once, per event, when it is computed. The invoice is the exact sum of its charges and is never rounded again.
- Unit prices (rates such as 0.00005 per KB) are **not** `Money`. They are plain `BigDecimal` with full precision, because scale 4 would round a small rate to zero. Only the resulting charge, `quantity x rate`, becomes `Money`, rounded once. (Found while writing the first test: `Money.of("0.00005")` collapses to `0.0000`.)
- One currency for now. Multi-currency is out of scope.
- `UsageEvent.quantity` is a positive `long` in the meter's smallest whole unit, so quantity itself never needs rounding.

## Why HALF_EVEN

`HALF_UP` always rounds exact halves away from zero, which biases totals upward across millions of events. `HALF_EVEN` sends halves to the even neighbour, so errors cancel out over many charges.

## Consequences

- Per-event rounding means the total can differ by tiny amounts from "sum first, round at the end". We accept this because the invoice must be explainable line by line.
- Scale 4 is a choice, not a law. Changing it later is a data migration, so it is fixed in one constant.
- Tier boundaries and time zones at period edges are decided in later tasks (6 and 10), not here.
