# 0006. Metrics: Micrometer, Prometheus format, no collector in the repository

Status: accepted (2026-10-03)

## Context

To see how the services behave under load (how long rating takes per event, whether Kafka consumption keeps up, how many events were rejected or dead-lettered) the services must report numbers, and the numbers must not tie the code to one monitoring product.

## Decision

- Every service exposes health and metrics through Spring Boot Actuator, using Micrometer, in the Prometheus text format at `/actuator/prometheus`. Micrometer keeps the code independent of the monitoring system; Prometheus format is understood by Prometheus, Grafana Agent, Datadog and others.
- Only `health`, `info` and `prometheus` are exposed. Endpoints that reveal configuration or memory (`env`, `heapdump`) or change state stay off, and a test checks that they answer `404`.
- Custom metrics describe the domain, not the framework: `ratekit.events.accepted` (ingest), `ratekit.events.processed{outcome}` and the timer `ratekit.rating.duration` with a histogram (rating), `ratekit.events.dead_lettered{cause}` (rating), `ratekit.invoices{result}` (billing). Kafka client metrics, including consumer lag, come with Spring Kafka.
- The rating timer is measured in the Kafka listener, outside the service's transaction, so it includes the database commit, which is where the time goes.
- Tag values stay few and fixed (outcome, cause, result). Account ids are never tags: each distinct value creates a new time series.
- No Prometheus or Grafana server is part of `compose.yaml`.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| Log lines only | Cannot be graphed or alerted on, and parsing logs for numbers is fragile. |
| Prometheus client library directly | Ties the code to one system; Micrometer gives the same output without that. |
| Add Prometheus and Grafana to Compose | The right next step, but the whole stack already uses about 1.7 GB of the 2 GB Podman VM; two more JVM-sized containers do not fit. |
| OpenTelemetry tracing | Useful once there are more hops; with three services and one queue the metrics answer the questions asked so far. |

## Consequences

- A collector can be added later without touching the services: point Prometheus at the three `/actuator/prometheus` URLs.
- The numbers in `docs/perf.md` were read from these endpoints with `curl`.
- Metrics are lost when a container restarts (counters start from zero), as with any pull-based setup without storage.
- Metric export is off by default inside `@SpringBootTest`; tests that read the Prometheus endpoint enable it with `@AutoConfigureObservability`.
