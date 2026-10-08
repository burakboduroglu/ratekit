# 0019. billing gets its own database, fed by a charge outbox in rating

Status: accepted (2026-10-08). Supersedes the "Shared database" consequence of ADR 0005 and ADR 0013.

## Context

billing read rating's `charges` table in the shared PostgreSQL database (ADR 0005). Five columns of rating's schema were a contract that nothing but a test file enforced, the two services had to agree on migration order (ADR 0013), and neither could be deployed, scaled, backed up or moved without the other. A database per service fixes that, but billing then needs another way to get every charge, and the readiness rule of ADR 0010 must still hold: before billing invoices a month it must know that every usage event accepted for the month has been rated **and** that every resulting charge is in billing's own table. billing can no longer look at rating's tables to find out.

The delivery rule of the project stays: at-least-once plus idempotent consumers. Nothing here is exactly-once.

## Decision

**Transactional outbox in rating.** `RatingService.handle` writes the charge and a row in `charge_outbox` (V6) in the same database transaction, together with the idempotency row and the balance deduction. A charge exists exactly when its outbox row does: a rollback removes both, and nothing is ever published for a charge that did not commit. The row only points at the charge (`charge_id`); the charge is never edited, so it is the payload. V6 also queues every charge stored before it, oldest first, so a new billing database receives the whole history.

**A polling relay.** `ChargeRelayService`, run every 0.5 s by `ChargeRelayScheduler`, works in batches of 500. One batch is one transaction:

1. `pg_try_advisory_xact_lock`: only one relay runs at a time across all rating instances; the others skip the pass.
2. Read `clock_timestamp()`, then the oldest unsent rows by id (READ COMMITTED: every row committed before that moment is visible to the read).
3. Send them to the topic `charges`, key `accountId`, record timestamp = when rating stored the charge, and wait for every acknowledgement (`acks=all`, idempotent producer).
4. Mark them `sent_at = now()` and commit.

A failure anywhere rolls the marks back and the rows are sent again on the next pass. Some may already be in Kafka, so billing can receive a charge twice; that is the "at-least-once" half.

**Order per account.** One account's events are rated one after another (one partition of `usage-events`, ADR 0002), so its outbox ids are in charging order; the single relay sends in id order; the idempotent producer keeps one partition's records in send order. A resend after a failure may repeat an older charge after a newer one; billing's idempotent insert makes that harmless, and billing only sums.

**Progress markers (watermarks).** When a pass has emptied the outbox, the relay writes a `ChargeFeedWatermark(publishedThrough, partitions)` to every partition of `charges`, at most every 2 s: "every charge committed before `publishedThrough` (rating's database clock, read in step 2) is on the topic now". It is written after those charges were acknowledged, so on each partition it comes after them. Both record types share the topic, told apart by a short type name in the `__TypeId__` header (`Topics.CHARGES_TYPE_MAPPING`). They cannot use separate topics: the marker is only meaningful because it sits behind the charges on the same partition.

**billing consumes idempotently.** `ChargeFeedListener` (group `ratekit-billing`) stores a charge in billing's own `charges` table (V2) with `INSERT ... ON CONFLICT (account_id, event_id) DO NOTHING`, and stores a marker as the partition's newest `published_through` in `charge_feed_watermarks` (never moving it backwards). The offset is committed after the handler, so a marker is stored only after every charge ahead of it on its partition. A record billing cannot store is retried for as long as it takes (back-off up to a minute, nothing classified as fatal): skipping it would let the next marker vouch for a charge billing does not have.

**The invoice run reads only billing's database**, and its readiness check becomes three steps:

1. Grace over and rating's consumer group past every usage record written before the cutoff (ADR 0010, unchanged). Then every usage event accepted for the month is a charge, a rejection or a dead letter, **committed in rating before this moment**.
2. billing reads its clock: `ratedBy`.
3. Every partition of `charges`, as many as the newest marker counts, has a stored marker at or after `ratedBy + clock-skew-margin` (1 s). Such a marker can only be written after `ratedBy`, so billing waits up to `charge-feed-wait` (30 s) for it, then refuses with `409` (`ChargesInFlightException`). The scheduler retries an hour later as before.

If step 3 passes, every charge committed before `ratedBy` was on the topic when the marker was written, sat ahead of the marker on its partition, and was stored by billing before the marker was. `ratekit.billing.rating-progress.enabled=false` switches off steps 1 and 3 together.

**Compose: a second PostgreSQL server** (`billing-postgres`, port 5433, user and database `billing`, its own volume), and `billing` points at it. A second database in the same server with separate roles would save about 40 MB, but needs an init script that only runs on a fresh volume, so every existing `pgdata` volume would have broken. A separate server also shows the boundary for what it is: the two can be upgraded, backed up and restarted on their own. rating creates the `charges` topic (three partitions) at startup, like the dead-letter topic; billing still starts after rating so the broker does not auto-create it first.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| Publish to Kafka inside the rating transaction (dual write) | A commit and a send cannot be atomic: a crash between them loses a charge or publishes one that rolled back. The outbox makes the database the only thing that has to commit. |
| Debezium (CDC on rating's WAL) | No polling and no relay code, but a Kafka Connect cluster, a replication slot and their operations; out of scope for this project. The outbox table is CDC-ready if it is ever wanted. |
| `FOR UPDATE SKIP LOCKED` with parallel relays | The usual way to scale an outbox, but two relays would send different batches of one account at the same time and break its order, and "everything before T is sent" would need every relay to agree. One relay at a time sends 500 rows per round trip, far above the measured rating rate; revisit with per-partition relays if it is ever the bottleneck. |
| Store the payload (JSON) in the outbox row | The usual outbox shape, decoupling the message from the table. Here the charge row is immutable and already holds every field, so a reference avoids a second copy. |
| billing asks rating over HTTP "is your outbox empty for the month?" | Answers step 2 of the problem but not step 3 (has billing consumed what was published?), adds a synchronous dependency on rating and needs rating's API key in billing. |
| billing checks its own consumer lag on `charges` against record timestamps | A charge still in the outbox leaves no trace in Kafka, so no offset or timestamp can say it is missing; and outbox id order is not commit order across accounts, so "a record after T exists" proves nothing about earlier ones. The marker is that check made explicit by the only party that knows: the relay. |
| billing's lag on `charges` is zero | Never true under steady traffic (the argument of ADR 0010). |

## Consequences

- billing never reads rating's tables; the contract between them is `ChargeEvent` and `ChargeFeedWatermark` in `common`. The test-only `charges` contract table and `MigrationOrderTest` are gone, and rating and billing no longer need Flyway's `baseline-on-migrate` (ADR 0013 is superseded).
- An invoice run waits a few seconds (until the next marker; at most `charge-feed-wait`) even on an idle system, because the marker it needs is written after it asked.
- The topic gets three small marker records every 2 s when idle (about 130,000 a day), and billing writes one row update per marker.
- **Remaining gaps, honestly:**
  - Clocks: `ratedBy` is billing's clock and the markers carry rating's database clock. A skew larger than `clock-skew-margin` with billing behind could let a marker vouch for a moment slightly before a charge committed. NTP keeps skew in milliseconds. Kafka 4 also rejects records stamped more than an hour ahead of the broker (`log.message.timestamp.after.max.ms`): a test run after the host had slept, with the Podman VM clock seven hours behind, failed exactly that way.
  - A charge billing cannot store blocks its partition (loud, by design) until someone fixes it; there is no dead-letter topic on billing's side.
  - If the `charges` topic loses records billing has not read (retention, 7 days by default, while billing is down), they are gone from Kafka; recovery is resetting `sent_at` in rating's outbox for the affected rows. billing's invoices that existed in the old shared database are not migrated; a dev stack starts over with `compose down -v`.
  - Sent outbox rows are kept, like the charges they point at (ADR 0014); pruning them is a later retention step.
  - A charge that reaches billing after its month was invoiced (a replayed dead letter) is stored but not invoiced; ADR 0020 handles it.
