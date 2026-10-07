# 0008. Accounts and top-ups over HTTP

Status: accepted (2026-10-07)

## Context

The balance model is prepaid with a hard stop, yet the only way to open an account or add money was SQL. A real caller (a shop, a payment callback) has to do both over an API, and adding money is the operation where a duplicate is most expensive: a payment callback that times out and is retried must not credit the account twice.

## Decision

- **Where:** `rating` serves the API, because it owns the `accounts` table and is the only writer of balances. A fourth service would mean a second writer to the same rows or a remote call inside every rating transaction.
- **Endpoints:** `POST /v1/accounts` opens an account (`201`, `409` if it exists), `GET /v1/accounts/{id}` reads the balance, `POST /v1/accounts/{id}/top-ups` adds money.
- **A new account starts at zero.** Money enters only through a top-up, so every credit leaves a row in the `top_ups` ledger and a balance can always be explained as top-ups minus charges.
- **Idempotency by a caller-chosen key.** The request carries a `topUpId`. `top_ups` has the primary key `(account_id, top_up_id)`; the service inserts the row with `ON CONFLICT DO NOTHING` and credits the balance only if a row was inserted, both in one transaction. A retry answers `200` with the current balance instead of `201`, so the caller can tell a first credit from a repeat. The same `topUpId` with a different amount is `409`: that is a client bug, not a retry, and guessing which amount is meant would be wrong either way.
- **The credit is one statement:** `UPDATE accounts SET balance = balance + x`, for the same reason the deduction is (ADR 0002): no read-modify-write that could lose a concurrent charge.
- **Amounts are strict.** Positive, at most four decimals (`@Digits(fraction = 4)`), sent as a string. Money coming in is refused rather than rounded silently (ADR 0001 rounds only what the system itself computes).

## Alternatives considered

| Option | Verdict |
| --- | --- |
| Top-up without an id, plain `balance + x` | A retried request credits twice. |
| Server-generated id returned to the client | The client does not have the id when the first answer is lost, which is exactly the case that needs it. |
| `Idempotency-Key` HTTP header | Same idea; a body field keeps the id in the ledger row and the OpenAPI schema without extra plumbing. Either is fine. |
| Initial balance on account creation | A second way for money to enter, with no ledger row. |
| A separate accounts service | Cleaner boundaries, but two writers to the balance or a network call inside rating's transaction. Not worth it at this size. |

## Consequences

- A test sends the same top-up ten times concurrently and gets exactly one `201`, nine `200` and one credit; a deliberate mutant that credits on every request fails it.
- There is **no authentication**. Anyone who reaches port 8082 can open accounts and add money. Acceptable for a local learning project, not for a deployment; it is on the list of known gaps.
- `rating` now serves HTTP traffic next to its Kafka consumer. Both share one connection pool; under heavy API load that could slow rating. To be measured if it ever matters.
- Tariffs still come from SQL. Managing them over the API is the next step (1b).
