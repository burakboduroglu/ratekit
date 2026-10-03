# Performance

What was measured on 2026-10-03, how, and what it does and does not say. Every number below comes from a real run; the commands to repeat them are at the end.

## Environment (read this before the numbers)

| | |
|---|---|
| Host | Apple M1, 8 cores, 8 GB |
| Where it all ran | one Podman VM with **4 CPUs and 2 GB**: PostgreSQL, Kafka (one broker, heap capped at 384 MB), `ingest`, `rating` (each limited to 512 MB, heap 50 %) **and the k6 load generator itself** |
| Data | 200 prepaid accounts, one flat tariff, events spread evenly over the accounts |
| Load | k6 `ramping-arrival-rate`, an open model: requests arrive at a set rate whether or not the server keeps up |

Everything shares four cores, so these numbers describe this laptop setup, not a production deployment, and the generator takes CPU away from the system it measures.

## Results

| Run | Load | Accepted | ingest latency (median, p95, max) | rating per event (mean, p95, p99) | max lag | Rating caught up |
|---|---|---|---|---|---|---|
| A: ramp, 1 consumer thread | 200 to 1600 req/s over 50 s | 33,278 | 1.4 ms, 152 ms, 2.6 s | 1.36 ms, <= 3 ms, <= 11 ms | 743 | 7 s after the load |
| B: sustained, 1 consumer thread | 1500 req/s for 45 s | 55,477 | 1.8 ms, 299 ms, 6.8 s | 1.01 ms, <= 2 ms, <= 6 ms | 4,113 | 12 s after the load |
| C: sustained, 3 consumer threads | 1500 req/s for 45 s | 45,989 | 4.4 ms, 343 ms, 19.4 s | 2.92 ms, <= 7 ms, <= 22 ms | 429 | 1 s after the load |

No request failed in A and B. In C, 0.36 % of the requests failed (timeouts at the saturated ingest). "Accepted" counts the `202` answers k6 saw.

What the numbers say:

- **Ingest answers most requests in a couple of milliseconds** (the median) but has a long tail: p95 is 150 to 340 ms and the slowest requests take seconds. The tail comes from saturation and JVM pauses, not from typical requests. The average (36 ms in run A) hides this, which is why the percentiles are reported.
- **One rating thread handles about 1000 events per second.** It spends 1.0 to 1.4 ms per event, including the database commit (Little's law: 1 / 1.01 ms is roughly 990 per second). In run B the load was 1500 per second, so a backlog of up to 4,113 records built up and was worked off 12 seconds after the load ended, at about 1,400 events per second.
- **Three rating threads cut the lag tenfold (4,113 to 429) but did not raise total throughput**: fewer events were accepted (45,989 against 55,477). Each event got slower (2.92 ms against 1.01 ms) because the threads compete for the same four cores, the same database and the same Kafka broker, and they take CPU from `ingest`, which was already running at about one full core. On dedicated machines more threads could help; here the limit is the shared CPU, not the partitions.
- **Partition count follows from the measured thread speed.** With about 1000 events per second per thread, a target of N events per second needs about N / 1000 consumer threads and therefore at least that many partitions. Three partitions is enough for the roughly 3,000 events per second that this design could reach on dedicated hardware; that figure was **not** measured here.

## A run that failed, and what it showed

A fourth run (3 consumer threads, 90 s at 1500 req/s, with k6's live web dashboard open) **ran the Podman VM out of memory**. The kernel killed the Kafka container (exit code 137, SIGKILL), 6.9 % of the requests failed and 98 thousand iterations were dropped. This is a resource limit of the 2 GB VM, not a defect of the code, and it is why the broker heap is capped and why the stack must not be run alongside `mvn verify`.

It also tested durability. After restarting only the broker, `rating` resumed from its committed offsets and finished: the topic held 34,975 messages and 34,975 charges were written, **none lost**. k6 had counted fewer `202` answers (34,159) because about 800 events were written to Kafka but their HTTP answers timed out on the client side. From a client's point of view that outcome is unknown, which is exactly why a retrying client is safe: the consumer is idempotent.

## Balance deduction strategies (ADR 0002)

`BalanceStrategyBenchmark` (off by default) compares three ways to deduct a balance, with 16 threads for 6 seconds each, against PostgreSQL 17 in a container:

| Strategy | One hot account (ops/s) | 200 accounts (ops/s) | Retries |
|---|---|---|---|
| Atomic `UPDATE ... WHERE balance >= x` (used by `rating`) | 13,773 | 20,736 | 0 |
| `SELECT ... FOR UPDATE`, compute, write | 1,748 | 7,032 | 0 |
| Optimistic (version column, retry on conflict) | 1,299 | 10,651 | 59,910 / 1,214 |

On a single busy account the atomic statement is about eight times faster than the row lock and ten times faster than optimistic locking, which needed 46 retries per successful update. With accounts spread out the gap narrows but the atomic statement still leads. This supports ADR 0002; it measures only the balance update, not the whole pipeline.

## What was not measured

- A longer run (soak) or a sudden spike.
- The load generator on a separate machine, a bigger VM, or a real multi-broker cluster.
- `billing`: invoice runs were not load tested.
- The effect of more than 3 partitions.

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

`load/run.sh` empties the charge tables. Leave `billing` stopped while load testing on a 2 GB VM.
