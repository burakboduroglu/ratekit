# 0007. Event time window at ingest

Status: accepted (2026-10-07)

## Context

`occurredAt` picks the tariff version and the billing month, and nothing limited it. An event dated in the future counted toward a month that had not started. An event dated in a month that billing had already invoiced was still rated: its cost came off the prepaid balance, but no invoice ever showed it, because an issued invoice is never rewritten (ADR 0005). The client got `202` either way.

## Decision

ingest checks `occurredAt` against its own clock before writing to Kafka:

- **Latest:** now plus `max-future-skew` (default 5 minutes), to allow for client clocks that run slightly fast.
- **Earliest:** the start of the calendar month (UTC, `BillingPeriod`) that was current `late-arrival-grace` ago (default 1 hour). Usage for a month is accepted until one hour after it ends; billing's scheduler runs at 02:00 UTC on the 1st, after the grace.
- Outside the window the answer is `422 Unprocessable Entity` with the reason, and nothing is written to Kafka. Not `400`, because the body is well-formed; not `409`, because no retry will ever succeed.
- Both values are configuration (`ratekit.ingest.event-time.*`). The compose file exposes the grace as `LATE_ARRIVAL_GRACE` so a demo can invoice last month without waiting.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| A fixed maximum age (for example 35 days) | Simple, but an event from an invoiced month can still be inside the age, so the silent gap stays. |
| Future limit only | Leaves the gap open until adjustment invoices exist. |
| Check in rating instead of ingest | The client has already been told `202`; the refusal would only show up as a dead letter. Refusing at the door gives the caller an answer it can act on. |
| Accept late usage and bill it on the next invoice as an adjustment | The right long-term answer for late CDRs in telecom, but it needs adjustment lines in billing. Out of scope for now. |

## Consequences

- No event can be charged to the balance for a month whose invoice is already out, **as long as the event passes ingest before the grace ends and rating keeps up**. An event accepted at 00:59 but still in Kafka at 02:00 is still missed; a lag check before the invoice run is a separate step.
- The window depends on the ingest server's clock. A wrong server clock refuses good events; servers run NTP.
- Usage that really arrives late (an offline device syncing days later) is refused. The sender must keep it and handle it, or the grace must be widened together with the billing schedule.
- Tests that send fixed dates now pin the clock (`Clock` bean, overridden in the integration test), and the load test sends the current time.
