# 0018. Opt-in SASL_SSL with per-service Kafka users and ACLs

Status: accepted (2026-10-08)

## Context

Every Kafka listener is `PLAINTEXT` and clients have no identity: anyone who can reach port 9092 can read every usage event, write forged ones, or read the dead-letter topic. ADR 0015 closed the HTTP side with a shared key; Kafka is the other door. The services need different rights: ingest only produces `usage-events`, rating consumes it (as group `ratekit-rating`) and produces `usage-events.dlq`, and billing only asks Kafka how far rating has got (ADR 0010: `describeTopics`, `listOffsets`, `listConsumerGroupOffsets`).

The default stack and the Testcontainers tests must stay as they are, because a plain broker is what a learner wants first.

## Decision

- **An overlay, `compose.secure.yaml`**, used as `podman compose -f compose.yaml -f compose.secure.yaml up -d --build`. `compose.yaml` is untouched; the overlay changes the broker and the three services' environment.
- **`SASL_SSL` with `SCRAM-SHA-512`** on both client listeners (HOST for the host, INTERNAL for containers). The controller listener stays `PLAINTEXT`, is not published and carries only the broker's own traffic, so the anonymous principal is a super user (as is `admin`). Users `ingest`, `rating`, `billing` and `admin` (the broker's inter-broker traffic and the CLI) are created when storage is formatted (`kafka-storage format --add-scram`), because the `apache/kafka` image formats storage itself but cannot add SCRAM users; the overlay formats first and lets the image's start script find it formatted.
- **`StandardAuthorizer`, deny by default.** A one-shot `kafka-init` container adds the ACLs, each service getting only what it uses:

  | User | Resource | Operations |
  | --- | --- | --- |
  | ingest | topic `usage-events` | Write, Describe, Create |
  | rating | topic `usage-events` | Read, Describe |
  | rating | group `ratekit-rating` | Read |
  | rating | topic `usage-events.dlq` | Write, Describe, Create |
  | billing | topic `usage-events` | Describe |
  | billing | group `ratekit-rating` | Describe |

  Create and Describe are there because ingest and rating make sure their own topic exists at startup (`KafkaAdmin`). For billing, `describeTopics` and `listOffsets` need Describe on the topic and `listConsumerGroupOffsets` needs Describe on the group (Kafka's authorization table for `OffsetFetch`, `Metadata` and `ListOffsets`); billing reads and writes nothing.
- **Services stay unchanged.** They read only Spring properties, set by environment in the overlay: `spring.kafka.security.protocol`, `spring.kafka.properties.sasl.mechanism`, `sasl.jaas.config` and `ssl.truststore.*`. Rating's `DeadLetterProducer` (built from `KafkaProperties.buildProducerProperties`) and billing's `KafkaRatingProgress` (`Admin.create` from `KafkaAdmin`'s properties) both build on the common properties, so they inherit the security settings; this was checked on the running stack (a dead letter was written, an invoice run passed the rating check).
- **Throwaway certificates**, made by `scripts/kafka-certs.sh` (keytool, PKCS12) into the untracked `.kafka-certs/`: a CA, a broker keystore with `kafka`, `localhost` and `127.0.0.1` as names, and a truststore with only the CA. Passwords come from the environment with development defaults. Nothing secret is committed.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| mTLS (client certificates as identity) | Strong and needs no password store, but every service needs its own certificate issued, mounted and rotated, and ACL principals become certificate subjects. Too much machinery for a learning stack; SCRAM gives the same per-service identity with a password. |
| SASL/PLAIN | Simple, but credentials live in the broker's JAAS file and the password crosses the wire as-is (safe only because of TLS). SCRAM stores salted hashes in the cluster metadata and never sends the password. |
| OAUTHBEARER | Right when there is an identity provider issuing tokens; there is none here, and the unsecured-token mode is for tests only. |
| `SASL_PLAINTEXT`, or TLS without authentication | Either leaves passwords or the data readable on the wire, or gives clients no identity, so ACLs cannot tell them apart. |
| Changing `compose.yaml` itself | Would make security mandatory and break the plain default; an overlay keeps both. |

## Consequences

- Opt-in: the default stack and all Testcontainers tests are unchanged, so the secure setup is verified by hand on the stack (see `docs/specs/local-dev.md`), not by `mvn verify`.
- SCRAM credentials are written at the first format and the broker keeps no volume, so changing a password means `down` and `up` again. In-memory storage also means ACLs are re-added by `kafka-init` on every start; it is idempotent.
- `podman-compose` does not honour `condition: service_completed_successfully` and starts everything at once; the services may start before the ACLs exist and recover through `restart: on-failure`. Docker Compose orders them properly.
- The controller listener is plaintext with an anonymous super user: acceptable because it is unpublished and single-node. A real cluster would secure it too.
- A shared PostgreSQL password, the HTTP API key (ADR 0015) and HTTP without TLS are separate gaps and stay as they are.
- The certificates live 30 days and the CA key stays on the developer's machine; this is not a production PKI.
