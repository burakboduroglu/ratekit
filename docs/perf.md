# Performance

What was measured on 2026-10-03, how, and what it does and does not say. Every number below comes from a real run; the commands to repeat them are at the end.

## Environment (read this before the numbers)

| | |
|---|---|
| Host | Apple M1, 8 cores, 8 GB |
| Where it all ran | one Podman VM with **4 CPUs and 4 GB**: PostgreSQL, Kafka (one broker, heap capped at 384 MB), `ingest`, `rating` (each limited to 512 MB, heap 50 %) **and the k6 load generator itself**. `billing` was stopped. |
| Data | 200 prepaid accounts, one flat tariff, events spread evenly over the accounts |
| Load | k6 `ramping-arrival-rate`, an open model: requests arrive at a set rate whether or not the server keeps up |

Everything shares four cores, so these numbers describe this laptop setup, not a production deployment, and the generator takes CPU away from the system it measures. The VM was first run with 2 GB; see the history below for why that was changed.

## Results (4 GB VM)

| Run | Load | Rating threads | Accepted | ingest latency (median, p95, max) | rating per event (mean, p95, p99) | Max lag | Rating caught up |
|---|---|---|---|---|---|---|---|
| A: ramp | 200 to 1600 req/s over 50 s | 1 | 34,988 | 0.47 ms, 2.9 ms, 0.53 s | 0.81 ms, <= 1 ms, <= 3 ms | 0 | 1 s after the load |
| B: sustained | 1500 req/s for 45 s | 1 | 66,267 | 0.43 ms, 30 ms, 1.3 s | 0.72 ms, <= 1 ms, <= 2 ms | 2,125 | 6 s after |
| C: sustained | 1500 req/s for 45 s | 3 | 66,245 | 0.85 ms, 54 ms, 1.2 s | 1.28 ms, <= 3 ms, <= 6 ms | 75 | 1 s after |
| D: longer, k6 dashboard open | 1500 req/s for 90 s | 3 | 133,289 | 0.66 ms, 26 ms, 1.1 s | 1.27 ms, <= 3 ms, <= 6 ms | 0 | 1 s after |
| E: stress | 3000 req/s for 45 s | 3 | 126,621 | 1.3 ms, 151 ms, 1.1 s | 1.27 ms, <= 3 ms, <= 6 ms | 11,547 | 12 s after |

No request failed in any of the five runs. "Accepted" counts the `202` answers k6 saw. In E, k6 could not start 8,375 of the planned requests (dropped iterations): the system, not the generator's schedule, was the limit.

What the numbers say:

- **Ingest answers in under a millisecond at the median** and stays in the tens of milliseconds at p95 until it is pushed past about 2,800 requests per second, where p95 rises to 150 ms. The tail comes from saturation and JVM pauses, not from typical requests. During the stress run `ingest` used about one full core (93 to 99 % in the samples), which makes it the first limit on this machine.
- **One rating thread handles about 1,400 events per second.** It spends 0.7 to 0.8 ms per event including the database commit (Little's law: 1 / 0.72 ms is about 1,390 per second). In run B the load was 1,500 per second, slightly above that, so a backlog of up to 2,125 records built up and was worked off 6 seconds after the load ended.
- **Three rating threads keep up where one cannot.** At 1,500 per second the lag stayed at 75 records instead of 2,125. Each event took longer (1.28 ms against 0.72 ms) because the threads share four cores and one database, but together they drained the 3,000 per second stress backlog at about 2,940 events per second. Accepted volume in B and C is the same, because the load was fixed at 1,500 per second and `ingest` was already keeping up.
- **Partition count follows from the measured thread speed.** About 1,400 events per second per thread on its own, and about 2,900 per second for three threads together on this shared machine; the shared CPU and the database, not the three partitions, were the limit. For a higher target, add partitions and consumer threads and measure again; the per-thread figure is the starting point.
- **The first limit on this machine is `ingest`'s CPU, then the database.** PostgreSQL reached 58 to 61 % in the long and stress runs.

## History: the same test on a 2 GB VM

The VM originally had 2 GB. The same kind of runs gave much worse numbers and one failure, because the stack, the load generator and the page cache did not fit:

| Run on 2 GB | Accepted | ingest latency (median, p95, max) | Rating per event (mean) | Max lag |
|---|---|---|---|---|
| ramp, 1 thread | 33,278 | 1.4 ms, 152 ms, 2.6 s | 1.36 ms | 743 |
| sustained 1500/s, 1 thread | 55,477 | 1.8 ms, 299 ms, 6.8 s | 1.01 ms | 4,113 |
| sustained 1500/s, 3 threads | 45,989 | 4.4 ms, 343 ms, 19.4 s | 2.92 ms | 429 |

A fourth run there (3 threads, 90 s, k6 dashboard open) **ran the VM out of memory**: the kernel killed the Kafka container (exit code 137, SIGKILL), 6.9 % of the requests failed and 98 thousand iterations were dropped. The identical run on the 4 GB VM (run D) finished with no failures. The lesson: **a number is only as good as the headroom behind it**; the 2 GB figures mostly measured memory pressure. They are kept as history, not as results.

That failure also tested durability. After restarting only the broker, `rating` resumed from its committed offsets and finished: the topic held 34,975 messages and 34,975 charges were written, **none lost**. k6 had counted fewer `202` answers (34,159) because about 800 events were written to Kafka but their HTTP answers timed out on the client side. From a client's point of view that outcome is unknown, which is exactly why a retrying client is safe: the consumer is idempotent.

## Balance deduction strategies (ADR 0002)

`BalanceStrategyBenchmark` (off by default) compares three ways to deduct a balance, with 16 threads for 6 seconds each, against PostgreSQL 17 in a container (measured on the 2 GB VM, before it was enlarged, with the stack stopped):

| Strategy | One hot account (ops/s) | 200 accounts (ops/s) | Retries |
|---|---|---|---|
| Atomic `UPDATE ... WHERE balance >= x` (used by `rating`) | 13,773 | 20,736 | 0 |
| `SELECT ... FOR UPDATE`, compute, write | 1,748 | 7,032 | 0 |
| Optimistic (version column, retry on conflict) | 1,299 | 10,651 | 59,910 / 1,214 |

On a single busy account the atomic statement is about eight times faster than the row lock and ten times faster than optimistic locking, which needed 46 retries per successful update. With accounts spread out the gap narrows but the atomic statement still leads. This supports ADR 0002; it measures only the balance update, not the whole pipeline.

## What was not measured

- A long soak run (hours) or a sudden spike.
- The load generator on a separate machine, or a real multi-broker cluster.
- `billing`: invoice runs were not load tested.
- More than 3 partitions or more than 3 consumer threads.
- The benchmark of balance strategies was not repeated on the 4 GB VM; only the ranking, not the absolute numbers, should be read from it.

## Observability used for these runs

Each service exposes Prometheus-format metrics at `/actuator/prometheus` (only `health`, `info` and `prometheus` are exposed). The numbers above come from them: `ratekit_rating_duration_seconds` (a histogram, so percentiles can be computed), `ratekit_events_processed_total{outcome}`, `ratekit_events_dead_lettered_total{cause}` and Kafka's own `kafka_consumer_fetch_manager_records_lag_max`. No Prometheus or Grafana server runs in this repository; the metrics were read with `curl`.

## Repeat it

```sh
docker compose up -d postgres kafka ingest rating        # or: podman compose up -d ...
COMPOSE="podman compose" CONTAINER=podman load/run.sh    # with Docker: load/run.sh
RATES=1500,1500,1500 STAGE_SECONDS=15 COMPOSE="podman compose" CONTAINER=podman load/run.sh
RATING_CONCURRENCY=3 podman compose up -d --force-recreate rating   # three consumer threads
DASHBOARD=1 COMPOSE="podman compose" CONTAINER=podman load/run.sh   # live charts at http://localhost:5665

mvn -pl rating test -Dtest=BalanceStrategyBenchmark -Dratekit.bench=true   # stop the stack first
```

`load/run.sh` empties the charge tables. Give the Podman VM at least 4 GB (`podman machine set --memory 4096`).
