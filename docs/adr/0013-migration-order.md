# 0013. rating and billing may migrate an empty database in either order

Status: accepted (2026-10-07)

## Context

rating and billing share one PostgreSQL database and each runs Flyway with its own history table (`flyway_schema_history` and `billing_schema_history`). billing was configured with `baseline-on-migrate: true, baseline-version: 0`, because rating's tables are normally there first. rating had no such setting. Compose hides the problem by starting billing only after rating is healthy, but nothing else does: started by hand (`java -jar`, an IDE) on an empty database with billing first, rating refused to start:

```
FlywayException: Found non-empty schema(s) "public" but no schema history table.
```

`MigrationOrderTest` (in billing) reproduced it by running each service's real Flyway settings, read from its `application.yml`, against an empty database in both orders.

## Decision

rating gets the same two settings billing has: `baseline-on-migrate: true` and `baseline-version: 0`. On an empty schema nothing changes (Flyway baselines only a non-empty schema). On a schema that holds only billing's tables, rating writes a baseline at version 0 into its own history table and then runs V1 onwards as usual.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| Move billing's tables into a schema of its own (`billing.invoices`) | Cleaner separation, and the right step if the services ever get separate databases. But billing reads `public.charges`, so it needs a search path or qualified names, and it changes billing's queries and tests for a start-up problem. |
| Document "start rating first" | A rule people forget; the failure only shows up on a fresh database. |
| One Flyway history for both services | Couples their release cycles: a billing migration would need rating's files and the other way round. |

## Consequences

- Either service may start first on an empty database; the test runs both orders and checks that nothing is left pending.
- A baseline at 0 can hide nothing real: V1 still runs, so if rating is ever pointed at a database that already holds rating's tables without a history, `V1__init.sql` fails loudly on the existing tables instead of being skipped.
- billing still needs rating's `charges` table at run time, so compose keeps starting billing after rating.
