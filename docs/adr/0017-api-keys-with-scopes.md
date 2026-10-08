# 0017. Per-caller API keys with scopes

Status: accepted (2026-10-07); supersedes [0015](0015-shared-api-key.md)

## Context

ADR 0015 guarded the `/v1` APIs with one shared key and named its limit: it cannot tell a shop that may only send usage from an operator who may change prices, and rotating it restarts every service. The key that a shop's backend holds could also open accounts, add money and start invoice runs. The question is still not "who are you?" (ratekit has no users or tenants) but "is this caller allowed to do this?".

## Decision

- **A list of named keys, each with scopes**, under `ratekit.security.keys`:

  ```
  ratekit.security.keys[0].name=shop
  ratekit.security.keys[0].key=...
  ratekit.security.keys[0].scopes=events:write
  ```

  As environment variables: `RATEKIT_SECURITY_KEYS_0_NAME`, `_KEY`, `_SCOPES` (comma separated).
- **Scopes** are `events:write` (ingest `POST /v1/events`), `accounts:read` and `accounts:write` (rating accounts and top-ups), `tariffs:read` and `tariffs:write` (rating tariffs), `billing:read` and `billing:write` (billing invoices and invoice runs). The names are the enum `ApiScope` in `common`, so a typo in configuration stops the service at startup on every service alike; each service has its own `EndpointScopes` that maps a request to the scope it needs: `GET` and `HEAD` need the `:read` scope of the area, any other method the `:write` one.
- **`401` for a missing or unknown key, `403` for a known key without the scope**, both as `ProblemDetail`; `401` also carries `WWW-Authenticate`. `403` names the missing scope (the caller already knows its own key) and never contains a key.
- **Deny by default.** A path under `/v1` that no scope covers gets `403` even for a key with every scope, so a new endpoint area has to be added to `EndpointScopes` to be reachable once keys are configured.
- **Kept from ADR 0015.** Off when no key is configured; only `/v1/*` is guarded (actuator and Swagger stay open); the filter is a servlet filter per service, not Spring Security; a blank key, a repeated name or key, a missing name or scopes, or an unknown scope stops the service at startup instead of opening the door.
- **Compared without leaking, now across several keys.** The given key is hashed with SHA-256 and compared with `MessageDigest.isEqual` against the hash of every configured key, without stopping at the first match, so the time says nothing about which key matched, how much of a guess was right or how long the key is. Only hashes are kept in memory. Keys are never logged.
- **The caller is visible.** Each request logs the caller's name at debug and puts it in the MDC as `caller` (the log level pattern shows it as `[shop]`), so a log line can be traced to a caller without ever printing a key.
- **Backward compatibility for one release.** If `ratekit.security.api-key` is set it becomes one more caller named `legacy` with every scope, and a deprecation warning is logged at startup. It is removed in the next release.
- **Compose** defines three keys once (`x-api-keys`): `shop` (`events:write`), `operator` (read and write on accounts, tariffs and billing) and `readonly` (the three read scopes), with local defaults that `SHOP_API_KEY`, `OPERATOR_API_KEY` and `READONLY_API_KEY` override. The README Quick start, `load/k6/ingest.js` (shop key) and `load/run.sh` use the key that fits each call.

## Alternatives considered

| Option | Verdict |
| --- | --- |
| Keep one key per service instead of scopes | Same blast radius inside a service: the key that adds money also changes prices. |
| Roles (`shop`, `operator`) instead of scopes | One more level of indirection between a key and an endpoint; scopes can be read off the endpoint list. Roles can be added later as named sets of scopes. |
| Spring Security with method security | Still a framework for what a small filter does; becomes the right tool with users, tenants or token issuing. |
| Scope per endpoint instead of per area and direction | Finer than any caller needs today; the area-and-direction rule fits on one screen. |
| Validate scopes per service | Each service would then reject a shared key list that names another service's scopes. One enum in `common` lets the same list be given to all three. |

## Consequences

- A leaked shop key can send usage for any account (no tenancy), but cannot read balances, add money, change prices or start invoices.
- Rotating one caller's key restarts the services with a new value for that entry only; the other callers are untouched. Rotation still needs a restart (keys are read at startup).
- Every service is given the whole key list in compose; a key's scopes that concern another service are ignored there.
- Existing deployments with `ratekit.security.api-key` keep working, with a warning, until the next release.
- Tests: per service, a filter test (`200` with the scope, `401` for missing and several wrong keys, `403` without the scope or on an uncovered path, caller in the MDC during the request and gone after), a scope-mapping test per endpoint and method, and a configuration test (off without keys, only `/v1/*` guarded, keys and legacy key bound, bad configuration refused); `ApiScope` is tested in `common`. Mutants that skip the scope check, ignore the legacy key or treat writes as reads fail them.
