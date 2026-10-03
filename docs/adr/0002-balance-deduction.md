# 0002. Prepaid balance deduction

Status: accepted (2026-10-03)

## Context

`rating` charges prepaid accounts. An event must be refused if the account cannot afford it (hard stop), and many events for one account can be processed at the same moment: several consumer instances, a rebalance that briefly overlaps two consumers, or a retry running next to a fresh event. The balance must never be overspent and no deduction may be lost.

## Decision

Deduct with one atomic statement:

```sql
UPDATE accounts SET balance = balance - :amount, updated_at = now()
WHERE id = :id AND balance >= :amount
```

One affected row means the amount was taken. Zero rows means the balance was too low: the event is recorded in `rejected_events` (with what it would have cost and the reason) and no charge is written. `CHECK (balance >= 0)` stays as a second guard.

A rejected event is still recorded in `processed_events`. A redelivery is therefore skipped, not re-evaluated, so topping up the account later does not silently revive old events. Reprocessing rejected events, if wanted, is an explicit operation, not a side effect of Kafka redelivery.

A zero-cost event (inside a free quota) always passes, even with an empty balance.

## Why not read, compute, write

Reading the balance, subtracting in application code and writing the result back loses updates: two transactions both read 10, both write 9, and one deduction vanishes. It also lets the check and the write disagree, so both pass the check against the same stale balance. A mutation test confirmed this: with that implementation, 60 racing events of cost 1.00 against a balance of 10.00 produced 18 accepted events instead of 10. With the single statement the result is exactly 10 accepted, 50 rejected, balance 0, over repeated runs.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| `SELECT ... FOR UPDATE`, then update | Correct and clear, but holds a row lock across application code and round trips. Candidate to compare under load. |
| Optimistic locking (version column, retry) | Cheap at low contention, retries pile up on a hot account. |
| Append-only ledger with a derived balance | Best audit trail, costlier reads and more moving parts. Possible later. |
| Reserve and commit (OCS style) | Needed for long sessions, out of scope here. |

The alternatives are compared with measurements in the load-test task, not by opinion.

## Consequences

- The check and the deduction cannot be separated by another transaction, whatever the number of consumer instances.
- A very busy single account serializes on its row lock. Accepted for now; measured later.
- This protects the balance only. Free-quota and tier usage is summed from `charges` before the deduction, so two events of the same account and meter running at the same instant could both see the same usage. Today this is prevented by Kafka's per-account ordering (the account is the message key, one consumer per partition), not by the database. Locking the account row at the start of processing would close the gap and is the first thing to evaluate if more concurrency is needed.
- Rejection is silent to the sender (the event was already accepted with 202). Surfacing rejections to clients is out of scope.
