# Local development

Infrastructure runs on Podman (no Docker installed). `compose.yaml` stays Docker-compatible.

## Start and stop

```sh
podman machine start          # once per boot, if the VM is not running
podman compose up -d          # postgres and kafka
podman ps                     # both should show (healthy)
podman compose down           # stop; add -v to also delete the postgres volume
```

## Images (pinned, checked on Docker Hub 2026-10-03)

| Service | Image | Host port |
|---|---|---|
| PostgreSQL | `postgres:17.11-alpine` | 5432 (user, password and db: `ratekit`) |
| Kafka (KRaft, single broker) | `apache/kafka:4.3.1` | 9092 |

Kafka has two client listeners: `localhost:9092` for apps on the host, `kafka:19092` for containers on the compose network (used from task 11). Default partitions per new topic: 3.

## Verify (as run on 2026-10-03)

```sh
kt() { podman exec ratekit_kafka_1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 "$@"; }
kt --create --topic usage-events --partitions 3
kt --list
kt --describe --topic usage-events
podman exec ratekit_postgres_1 psql -U ratekit -d ratekit -tc 'select 1'
```

Container names follow `ratekit_<service>_1` under podman-compose.
