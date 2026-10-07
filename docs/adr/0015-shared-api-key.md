# 0015. A shared API key on the HTTP APIs

Status: accepted (2026-10-07)

## Context

Since ADR 0008 and 0009, anyone who can reach port 8082 can open accounts, add money and change prices; anyone who reaches 8081 can spend other accounts' balances, and 8083 can start invoice runs. Both ADRs listed the missing authentication as a known gap. ratekit has no users, roles or tenants (multi-tenancy is out of scope), so the question is only "is this caller one of ours?".

## Decision

- **One shared secret**, `ratekit.security.api-key`. When it is set, every request under `/v1` on ingest, rating and billing must carry it in the `X-Api-Key` header; otherwise the answer is `401` with a `ProblemDetail` and a `WWW-Authenticate: ApiKey header="X-Api-Key"` header, and the request never reaches a controller.
- **Only `/v1`.** `/actuator` (health checks, Prometheus scraping) and the OpenAPI pages stay open; the filter is registered for the URL pattern `/v1/*`, so a new endpoint under `/v1` is guarded by default.
- **Off unless configured.** With the property unset nothing changes, so tests and a jar started from an IDE work as before. Compose always sets it (`RATEKIT_SECURITY_APIKEY`, default `local-dev-key`, overridden by `RATEKIT_API_KEY`), and the README demo and the k6 load test send it. A key that is set but blank stops the service at startup instead of opening the door.
- **Compared without leaking.** Both keys are hashed with SHA-256 and the hashes compared with `MessageDigest.isEqual`, so response time reveals neither how much of a guess matched nor the key's length. The key is never logged.
- **A servlet filter per service**, about 60 lines, not Spring Security. Three copies of a small class are cheaper to read than a security framework configured three times; `common` stays framework-free, so it cannot hold the filter.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| Spring Security with HTTP Basic or an API-key filter | The standard path once there are users or roles; for one shared secret it adds a filter chain, default login pages to switch off and CSRF rules to understand, for the same result. |
| OAuth 2 / JWT resource server | Right when callers have identities and scopes; needs an authorization server. Revisit with multi-tenancy. |
| mTLS between callers and services | Strong, but certificates for a local learning stack are a larger lesson of their own. |
| Network isolation only (do not publish the ports) | Still the first defence, and still recommended; but one exposed port should not mean free money. |

## Consequences

- Every caller needs the key, including scripts: the README Quick start, `load/k6/ingest.js` and `load/run.sh` send it. Requests made by the services among themselves do not exist (they talk through Kafka and the database), so nothing internal changes.
- One key for everything: it cannot tell a shop that may only send usage from an operator who may change prices, and rotating it means restarting the three services with a new value. Per-caller keys or real identities belong with multi-tenancy.
- The key travels in a header over plain HTTP locally; outside a laptop it needs TLS in front.
- Tests: per service, a filter unit test (right key passes; missing, wrong, differently cased or padded keys get `401`; a blank configured key is refused) and a configuration test (no key, no filter; with a key, only `/v1/*` is guarded). A deliberate mutant that accepts any key fails them.
