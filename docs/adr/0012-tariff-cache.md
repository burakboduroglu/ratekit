# 0012. Cache tariff versions per meter in rating

Status: accepted (2026-10-07); amended 2026-10-07: a new version must start one TTL after now

## Context

Rating read every version of the event's meter from `tariffs` for every event, although versions change rarely and are never edited (ADR 0009). With the usage counter (ADR 0011) this query was one of the two remaining reads per event that did not depend on the event itself.

## Decision

- `TariffBookCache` keeps the `TariffBook` of each meter in a `ConcurrentHashMap` for `ratekit.rating.tariff-cache.ttl` (default 30 s, `PT0S` turns it off). No cache library: one map and a timestamp per entry are enough, and they are easy to read and test.
- **A version added through this instance's API evicts its meter after the insert commits** (a transaction synchronization in `TariffService`). Evicting before the commit would let the listener reload the old versions while the new row is still invisible and keep them for a whole TTL.
- **Amended 2026-10-07: a new version starts no earlier than now plus the TTL.** `TariffService` answers `422` for an earlier `effectiveFrom` and uses now plus the TTL when it is omitted (this also amends ADR 0009's "not before now"). Every other instance loaded its cached versions at most one TTL before the insert, so by the start they have expired and reloaded; the multi-instance gap below cannot occur. The same-instance eviction stays, so the API instance itself is not delayed. The rule uses the configured TTL of the instance that takes the request; instances must share one TTL.
- **If the cached versions have none that applies at the event's time, the meter is read again** before rating gives up. Without this, a meter's first tariff added on another instance would dead-letter its events (no tariff is a permanent error, ADR 0004) for up to a TTL.

## Why a stale cache cannot misprice

*Before the amendment this section showed a gap of up to one TTL on other instances; the start rule above closes it. The reasoning is kept because it shows why the rule is needed.* A new version may not start before now (ADR 0009). Suppose rating cached the versions at `L` and a new one is added at `C` (so `L < C`) starting at `T >= C`. An event that happens at or after `T` must be priced with the new version.

- Same instance: the eviction runs right after the commit at `C`, so the next event reloads. The only gap is between the check of `effectiveFrom` and the commit, a few milliseconds in which the new row is not visible to anyone, cache or not; that gap existed before the cache.
- Another instance: it keeps the old versions until `L + TTL`, so events in `[T, L + TTL)` would be priced with the old version if `T` could be as early as `C`. With `T >= C + TTL` and `L < C`, `L + TTL < T`: that instance's entry has expired by `T`. (Events whose `occurredAt` is up to five minutes ahead, ADR 0007, can be processed before `T`; they are priced with the versions loaded at that moment, as before.)

## Alternatives considered

| Option | Verdict |
| --- | --- |
| No cache | One query per event for data that changes a few times a year. |
| Caffeine or Spring's cache abstraction | Size limits and statistics we do not need; one more dependency to learn and configure. Revisit if the number of meters grows large. |
| Invalidate other instances through Kafka | Correct across instances, but a new topic and consumer for a problem that a version starting one TTL in the future avoids. |
| Require `effectiveFrom >= now + TTL` | Closes the multi-instance gap completely, at the price of changing ADR 0009's "omit for now" contract. First kept as an operating rule only; **adopted 2026-10-07** because a rule in code cannot be forgotten. |

## Consequences

- One query per meter per 30 s instead of one per event.
- A price change cannot take effect sooner than one TTL (30 s by default) after the request. With `PT0S` (cache off) it can start now, as before. Raising the TTL on some instances only would weaken the guarantee: all rating instances must use the same value.
- Versions inserted with SQL (seeding, the demo) are picked up within one TTL, or at once if no cached version applies to the event.
- The cache holds every meter ever seen; with a handful of meters that is nothing. A bound is a change for the day there are many.
