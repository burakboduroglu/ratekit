# 0003. Package structure: layers per service, a pure domain

Status: accepted (2026-10-03)

## Context

The first versions kept each service in one or two flat packages. `ingest` had six classes side by side, and a few classes did more than one job: the tariff repository ran SQL and also parsed JSON into price models, the Kafka config declared a topic and also configured serialization, the request DTO converted itself into a domain event. Someone opening the code could not tell where a controller ends and business logic begins.

## Decision

Each service is split into conventional layers, one package per layer, and a class does one job.

| Package | Holds | Rule |
| --- | --- | --- |
| `controller` | HTTP entry points | Translate the request, call a service, translate the answer. No business logic. |
| `dto` | Request and response shapes of the API | Plain data. No behaviour, no conversion. |
| `mapper` | Conversions between layers (DTO to domain, database row to domain) | One conversion concern per class. |
| `service` | Use cases, orchestration | Coordinates repositories and the domain. Owns transactions. |
| `repository` | Database access | SQL only. |
| `messaging` | Kafka producers and listeners (adapters) | Translate between Kafka and the service layer. |
| `config` | Spring configuration beans | One concern per class. |
| `exception` | Exception types and their HTTP translation | Controllers stay free of error handling. |
| `domain` (rating) | Pricing rules: price models, tariffs, the rater | Plain Java. No Spring, Kafka or JDBC imports; a test fails the build if one appears. |

Dependencies point inward: controller and messaging call service, service calls repository and domain, the domain depends on nothing. `common` holds only what several services must agree on (event contract, money rules, topic names).

The pure `domain` package is called `domain`, not `model`, because it carries behaviour (`Rater`, `TieredPrice`), not only data. A package of data-only classes with logic elsewhere is an anemic model.

## Consequences

- A reader can find any class by its role.
- A thin class such as `EventIngestService` (one call today) is deliberate: it is the place where ingest rules will go, and the controller never talks to Kafka directly.
- More files and a few `public` modifiers that package-private classes did not need. Accepted for the clarity.
- `billing` follows the same layout when it is implemented.
