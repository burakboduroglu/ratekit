# Local development

Everything runs on Podman (no Docker installed here). `compose.yaml` and the `Dockerfile` stay Docker-compatible.

## Two ways to run

**A. Everything in containers** (PostgreSQL, Kafka and the three services, built from source):

```sh
podman machine start                 # once per boot, if the VM is not running
podman compose up -d --build         # first build takes a few minutes, later ones use the cache
podman ps                            # five containers, all (healthy)
podman compose down                  # stop; add -v to also delete the PostgreSQL volume
```

**B. Services on the host** (for debugging in an IDE): start only the infrastructure and run the jars yourself.

```sh
podman compose up -d postgres kafka
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
mvn -B verify
java -jar ingest/target/ingest-0.1.0-SNAPSHOT.jar      # and rating, billing
```

## Images (pinned, checked 2026-10-03)

| Service | Image | Host port |
|---|---|---|
| PostgreSQL | `postgres:17.11-alpine` | 5432 (user, password and db: `ratekit`) |
| Kafka (KRaft, single broker) | `apache/kafka:4.3.1` | 9092 |
| ingest | built from `Dockerfile` (`SERVICE=ingest`) | 8081 |
| rating | built from `Dockerfile` (`SERVICE=rating`) | 8082 |
| billing | built from `Dockerfile` (`SERVICE=billing`) | 8083 |

Kafka has two client listeners: `localhost:9092` for apps on the host, `kafka:19092` for containers on the compose network. The service containers get their addresses from environment variables (`SPRING_KAFKA_BOOTSTRAP_SERVERS`, `SPRING_DATASOURCE_URL`), which override the `application.yml` defaults.

## The Dockerfile

One `Dockerfile` at the repository root builds any service: `--build-arg SERVICE=ingest|rating|billing`. It has to be the root because every service needs the parent pom and `common`.

- **Multi-stage:** a Maven + JDK 21 stage compiles (`maven:3.9.16-eclipse-temurin-21`), a JRE-only stage runs (`eclipse-temurin:21.0.12.1_1-jre`). The image carries no compiler and no source.
- **Layer cache:** the poms are copied before the sources, and Maven's local repository is a build cache mount, so a source-only change does not download dependencies again.
- **Non-root:** the process runs as a system user `app` (uid 10001).
- **No tests during the build:** the integration tests start their own containers, so they run in CI (and with `mvn verify`), not while building an image.
- **`.dockerignore`** keeps `target/`, `.git` and docs out of the build context.

## Start order and health

`depends_on` with `condition: service_healthy` makes the order explicit: PostgreSQL and Kafka first, then `ingest` and `rating`, and `billing` last because it reads the `charges` table that `rating`'s migration creates. The services' healthcheck calls `/actuator/health` with `curl` (present in the `eclipse-temurin` JRE image) and requires `"status":"UP"`, so a service counts as healthy only when Spring reports it ready, including its database connection, not merely when its port is open. `restart: on-failure` covers a slow dependency.

## Memory (read this)

The whole stack uses about 1.8 GB of RAM inside the Podman VM: Kafka ~350 MB with its heap capped at 384 MB (`KAFKA_HEAP_OPTS`), each Spring service ~230 MB (`MaxRAMPercentage=50`, `mem_limit: 512m`), PostgreSQL ~65 MB, plus the kernel and page cache.

- **Podman's default VM has 2 GB, which is too little.** On it the stack ran at the edge: with the broker heap uncapped the kernel killed Kafka (exit code 137, SIGKILL) and `ingest` then answered `503`; with the heap capped it still happened again during load tests and when `mvn verify` ran next to the stack. On 2026-10-03 the VM was raised to 4 GB (`podman machine stop && podman machine set --memory 4096 && podman machine start`, an Apple M1 with 8 GB) and none of this recurred, including a 90 second load test with three rating threads.
- **On a 2 GB VM**, stop the stack before `mvn verify` (Testcontainers starts a second Kafka and PostgreSQL) and keep `billing` stopped during load tests.
- More detail on what was measured: `docs/perf.md`.

## Metrics and load test

Each service exposes `/actuator/health` and `/actuator/prometheus` on its port (8081, 8082, 8083). `load/run.sh` drives ingest with k6 (run as a container, nothing to install) and reports how long rating needs to catch up; set `DASHBOARD=1` for k6's live charts on http://localhost:5665. Commands and results are in `docs/perf.md`. Rating's consumer threads are set with `RATING_CONCURRENCY` (default 1).

## Verify (as run on 2026-10-03)

Since ADR 0008 `seed-demo.sql` holds only the tariff; open `acc-demo` and top it up through `POST /v1/accounts` first. Since ADR 0007, ingest refuses usage for a closed month, so replaying the September events below needs `LATE_ARRIVAL_GRACE=P62D` (or wider) on `ingest`; the Quick start in the README shows the current flow.

```sh
psq() { podman compose exec -T postgres psql -U ratekit -d ratekit "$@"; }
psq < scripts/seed-demo.sql
curl -X POST localhost:8081/v1/events -H 'Content-Type: application/json' \
  -d '{"eventId":"e-1","accountId":"acc-demo","meter":"sms","quantity":95,"occurredAt":"2026-09-15T10:00:00Z"}'
curl -X POST localhost:8081/v1/events -H 'Content-Type: application/json' \
  -d '{"eventId":"e-2","accountId":"acc-demo","meter":"sms","quantity":10,"occurredAt":"2026-09-15T11:00:00Z"}'
psq -c "SELECT event_id, quantity, amount FROM charges ORDER BY id;"       # 0.0000 and 0.2500
curl -X POST localhost:8083/v1/invoice-runs -H 'Content-Type: application/json' -d '{"period":"2026-09"}'
curl "localhost:8083/v1/invoices/acc-demo?period=2026-09"                  # total 0.2500
```

A burst of 50 more events produced 50 charges, an event for an unknown account landed in `usage-events.dlq`, and all five containers stayed healthy.

Container names follow `ratekit_<service>_1` under podman-compose.
