# 0004. Retry and dead-letter policy

Status: accepted (2026-10-03)

## Context

Kafka delivers a record, `rating` tries to process it, and sometimes that fails. Before this decision a failed record was retried about nine times, half a second apart, which held up every record behind it on the same partition, and was then dropped with a log line. A real run showed an event vanishing this way. Two things were wrong: waiting was applied to failures that waiting cannot fix, and a record that finally failed was lost.

## Decision

Failures are split by whether time can help.

| Kind | Examples | What happens |
| --- | --- | --- |
| Transient | database briefly unreachable, timeout | Retried with growing pauses, then dead-lettered if it still fails |
| Permanent | unknown account, meter with no tariff, a tariff row that is invalid, usage that overflows its counter, a message that is not valid JSON | Dead-lettered at once, no waiting |

- Retries: 4 retries after the first attempt, pauses of 0.5 s, 1 s, 2 s and 4 s (7.5 s at most), configurable under `ratekit.rating.retry.*`. The partition is held for at most that long.
- A dead-lettered record is published to `usage-events.dlq` with its original key (so one account's dead letters stay together) and headers that name the original topic, partition and offset, the consumer group and the exception, including its stack trace. A message that could not be parsed is stored byte for byte.
- After the record is dead-lettered the consumer moves on. If publishing to the dead-letter topic itself fails, the record is retried rather than lost.
- The failure of one record leaves no database trace: processing runs in one transaction, so the "processed" mark is rolled back with everything else.
- Replaying dead letters is a manual, deliberate operation. Nothing resends them automatically, because an automatic loop would just fail again for a permanent error.

## Why not the alternatives

- Retry everything the same way: permanent failures would block the partition for 7.5 s each for no benefit.
- Never retry: a one-second database blip would dead-letter good events.
- Retry forever: one bad record would stop its partition indefinitely.
- Skip and log (the old behaviour): data loss.

## Consequences

- No event is silently lost: it is either rated, rejected and recorded (balance), or dead-lettered with the reason.
- A transient fault can still delay a partition for up to 7.5 s. That is the price of not dead-lettering good events; the numbers are configurable.
- Operations must watch the dead-letter topic. A growing topic means something is wrong upstream (accounts or tariffs not provisioned before usage arrives). Alerting on it belongs to the observability task.
- Unknown-account checking costs one extra primary-key lookup per event. To be measured in the load test.
- The dead-letter producer takes its broker address from Spring Boot's `KafkaConnectionDetails`, not only from `spring.kafka.bootstrap-servers`. The first version used the property alone and, in tests, wrote dead letters to the developer's local broker instead of the test container; an integration test exposed it.
- Amended 2026-10-07: an invalid tariff row (bad JSON, a missing or invalid field) used to surface as a plain `NullPointerException` or `IllegalArgumentException`, which counted as transient. Every event on that meter then held its partition for 7.5 s before being dead-lettered anyway. `TariffMapper` now wraps any such failure in `InvalidTariffException`, which is permanent, and `ArithmeticException` (usage overflowing a `long`) is permanent too.
