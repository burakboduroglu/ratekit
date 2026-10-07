# 0009. Tariff versions over HTTP

Status: accepted (2026-10-07)

## Context

Tariffs could only be added with SQL. Nothing checked the JSON parameters on the way in, so a typo became a row that rating could not read; since ADR 0004's amendment such a row dead-letters every event of its meter at once, which is safe but still loses the sale. Nothing stopped a version from starting in the past either, which silently changes the price of a period that is partly rated already.

## Decision

- **Where:** `rating`, next to the account API (ADR 0008), because it owns the `tariffs` table and is the code that reads it.
- **Endpoints:** `POST /v1/tariffs` adds a version; `GET /v1/tariffs?meter=` lists a meter's versions oldest first. There is no update and no delete: a price change is a new version (the rule from `V1__init.sql`).
- **Checked by the reader.** The service builds the price model with `TariffMapper`, the same class rating uses when it prices an event. A tariff that would fail there fails here, with `400` and the reason. The mapper also became stricter: `freeUnits` and tier bounds must be JSON whole numbers. Before, Jackson's `asLong()` turned `"abc"` into 0 and `1.5` into 1, so a typo became a different price instead of an error.
- **No retroactive prices.** `effectiveFrom` must not be before now (`422`); when omitted it is now. Events up to now were priced with the old version; one that reaches back would price the rest of that span differently, and a re-rating would not reproduce the charges already stored.
- **One version per instant:** the existing `UNIQUE (meter, effective_from)` gives `409`.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| Allow past `effectiveFrom` for back-office corrections | That is re-rating, a separate feature with its own consistency problems (adjust balances, invoices). SQL remains for seeding history, as the demo does. |
| Validate with a JSON schema | A second definition of what a valid tariff is, which can drift from the parser. Using the parser itself cannot drift. |
| `PUT` to edit a version | Breaks the rule that an old event can always be priced again with the tariff that applied then. |
| `effectiveFrom` required | Forces clients to guess the server's "now" and race it. Omitting it is the common case. |

## Consequences

- A meter's price can change mid-month. The free quota and tiers keep counting the units already used this month across the change (Rater works on the month's total), which is the usual telecom behaviour.
- Seeding past versions (demo, migration of old data) still needs SQL, and SQL bypasses the checks. That is an operator action, not an API one.
- Still no authentication; anyone who reaches port 8082 can change prices.
