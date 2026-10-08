# Local development

Everything runs on Podman (no Docker installed here). `compose.yaml` and the `Dockerfile` stay Docker-compatible.

## Two ways to run

**A. Everything in containers** (PostgreSQL, Kafka and the three services, built from source):

```sh
podman machine start                 # once per boot, if the VM is not running
podman compose up -d --build         # first build takes a few minutes, later ones use the cache
podman ps                            # six containers, all (healthy)
podman compose down                  # stop; add -v to also delete the PostgreSQL volume
```

**B. Services on the host** (for debugging in an IDE): start only the infrastructure and run the jars yourself.

```sh
podman compose up -d postgres billing-postgres kafka
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
mvn -B verify
java -jar ingest/target/ingest-0.1.0-SNAPSHOT.jar      # and rating, billing
```

## Git hook

Enable it once per clone: `git config core.hooksPath .githooks`. `.githooks/pre-commit` checks only the staged changes, in well under a second, with nothing but `git` and `grep`: it refuses merge-conflict markers, private keys, cloud and GitHub/Slack tokens, and anything under `docs/learning/` (personal notes, kept out of the public history). It does not build or test; CI does. For a false alarm, check it and commit with `--no-verify`.

## Images (pinned, checked 2026-10-03)

| Service | Image | Host port |
|---|---|---|
| PostgreSQL (rating) | `postgres:17.11-alpine` | 5432 (user, password and db: `ratekit`) |
| PostgreSQL (billing, ADR 0019) | `postgres:17.11-alpine` | 5433 (user, password and db: `billing`) |
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

`depends_on` with `condition: service_healthy` makes the order explicit: PostgreSQL and Kafka first, then `ingest` and `rating`, and `billing` last because `rating` creates the `charges` topic it reads (otherwise the broker would auto-create it with its defaults). billing's own PostgreSQL (`billing-postgres`) is a separate server with its own volume (ADR 0019). The services' healthcheck calls `/actuator/health` with `curl` (present in the `eclipse-temurin` JRE image) and requires `"status":"UP"`, so a service counts as healthy only when Spring reports it ready, including its database connection, not merely when its port is open. `restart: on-failure` covers a slow dependency.

## Memory (read this)

The whole stack uses about 1.8 GB of RAM inside the Podman VM: Kafka ~350 MB with its heap capped at 384 MB (`KAFKA_HEAP_OPTS`), each Spring service ~230 MB (`MaxRAMPercentage=50`, `mem_limit: 512m`), PostgreSQL ~65 MB, plus the kernel and page cache.

- **Podman's default VM has 2 GB, which is too little.** On it the stack ran at the edge: with the broker heap uncapped the kernel killed Kafka (exit code 137, SIGKILL) and `ingest` then answered `503`; with the heap capped it still happened again during load tests and when `mvn verify` ran next to the stack. On 2026-10-03 the VM was raised to 4 GB (`podman machine stop && podman machine set --memory 4096 && podman machine start`, an Apple M1 with 8 GB) and none of this recurred, including a 90 second load test with three rating threads.
- **On a 2 GB VM**, stop the stack before `mvn verify` (Testcontainers starts a second Kafka and PostgreSQL) and keep `billing` stopped during load tests.
- More detail on what was measured: `docs/perf.md`.

## Metrics and load test

Each service exposes `/actuator/health` and `/actuator/prometheus` on its port (8081, 8082, 8083). `load/run.sh` drives ingest with k6 (run as a container, nothing to install) and reports how long rating needs to catch up; set `DASHBOARD=1` for k6's live charts on http://localhost:5665. Commands and results are in `docs/perf.md`. Rating's consumer threads are set with `RATING_CONCURRENCY` (default 1).

## Secure Kafka

Opt-in, for trying Kafka with authentication, encryption and ACLs ([ADR 0018](../adr/0018-kafka-transport-security.md)). The default stack stays plaintext.

```sh
scripts/kafka-certs.sh        # throwaway CA, broker keystore, truststore, admin client.properties -> .kafka-certs/ (untracked); needs keytool
podman compose -f compose.yaml -f compose.secure.yaml up -d --build
podman compose -f compose.yaml -f compose.secure.yaml down       # same two files; the broker keeps no volume
```

- **What changes:** the HOST (`localhost:9092`) and INTERNAL (`kafka:19092`) listeners use `SASL_SSL` with `SCRAM-SHA-512`; the controller listener stays internal. Users: `ingest`, `rating`, `billing` and `admin` (broker and CLI). A one-shot `kafka-init` container adds ACLs: ingest writes `usage-events`; rating reads it as group `ratekit-rating` and writes `usage-events.dlq`; billing may only describe `usage-events` and the group (all it needs for `describeTopics`, `listOffsets` and `listConsumerGroupOffsets`).
- **Passwords** are development defaults (`ratekit-dev-<user>`, store password `ratekit-dev-store`); override with `KAFKA_ADMIN_PASSWORD`, `KAFKA_INGEST_PASSWORD`, `KAFKA_RATING_PASSWORD`, `KAFKA_BILLING_PASSWORD` and `KAFKA_STORE_PASSWORD` (the same variables for `scripts/kafka-certs.sh` and `up`). SCRAM users are written when the broker is first formatted, so after a change run `down` first.
- **Services** need no code change: the overlay sets `SPRING_KAFKA_SECURITY_PROTOCOL`, `SPRING_KAFKA_PROPERTIES_SASL_MECHANISM`, `..._SASL_JAAS_CONFIG` and `..._SSL_TRUSTSTORE_*`.
- **CLI:** `.kafka-certs/secrets` is mounted at `/etc/kafka/secrets` in the broker, with `client.properties` holding the admin login. The dead-letter command from the README becomes:

  ```sh
  podman compose -f compose.yaml -f compose.secure.yaml exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --command-config /etc/kafka/secrets/client.properties \
    --topic usage-events.dlq --from-beginning
  ```

- **`podman-compose` ignores `service_completed_successfully`**, so the services may start before the ACLs are in; they fail, and `restart: on-failure` brings them up within a few seconds.
- Not covered by `mvn verify` (the Testcontainers tests use a plain broker).

Verified on 2026-10-08 (Podman, `apache/kafka:4.3.1`): the README Quick start worked unchanged (charges `0.0000` and `0.2500`, balance `99.7500`), an event for an unknown account reached `usage-events.dlq` through rating's own producer, and an invoice run answered `{"invoicesCreated":0,"alreadyInvoiced":0}` after billing's admin calls succeeded. Denied as intended: reading `usage-events` as `ingest` (`GroupAuthorizationException`), writing `usage-events` as `billing` (`ClusterAuthorizationException`), writing `usage-events.dlq` as `ingest` (`TOPIC_AUTHORIZATION_FAILED`), a wrong password (`SaslAuthenticationException`) and a `PLAINTEXT` client (timeout).

## Verify (as run on 2026-10-03)

Since ADR 0017 every `/v1` call below also needs an `X-Api-Key` header: `local-shop-key` for events, `local-operator-key` for everything else (compose's defaults). Since ADR 0008 `seed-demo.sql` holds only the tariff; open `acc-demo` and top it up through `POST /v1/accounts` first. Since ADR 0007, ingest refuses usage for a closed month, so replaying the September events below needs `LATE_ARRIVAL_GRACE=P62D` (or wider) on `ingest`; the Quick start in the README shows the current flow.

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
