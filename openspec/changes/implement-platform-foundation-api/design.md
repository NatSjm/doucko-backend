# Design — implement-platform-foundation-api

## Goals / Non-goals

Goals: each backend-owned cross-cutting NFR gets an executable check that
later slices extend, not rebuild. Non-goals: any contract operation, admin
authentication (slice 2), the first table (slice 4).

## Key decisions

1. **Compose lives here (D-P1).** The `frontend` service builds from
   `../frontend` (sibling checkout), so `docker compose up` needs both repos
   side by side, the same layout `contract/` already assumes.
2. **Bind address is one variable.** Every published port is
   `${DOUCKO_BIND_ADDRESS:-127.0.0.1}:<host>:<container>`. Compose cannot
   validate it, so `tools/stack.mjs` refuses to start unless the address is
   loopback or RFC 1918 (`10/8`, `172.16/12`, `192.168/16`). The database
   publishes no port (admin-access "Database not published").
3. **Backup through `docker compose exec`.** `pg_dump -Fc` streams to the
   host as binary, which avoids Windows newline and console code-page
   mangling. Output defaults to `~/doucko-backups/` (outside every repo,
   because the pre-commit hook forbids `backups/` paths). `restore` uses
   `pg_restore --clean --if-exists`.
4. **Baseline security without auth.** With Spring Security on the
   classpath and no configuration, every request needs HTTP Basic with a
   generated password. The baseline chain permits everything except
   `/api/admin/**` (`denyAll` until slice 2) and turns CSRF off for
   `/api/child/**`, which has no session (contract `info`). Slice 2 replaces
   the admin rule.
5. **Problem titles from `messages.properties`.** Spring's
   `ResponseEntityExceptionHandler` resolves `problemDetail.title.<exception>`
   and `problemDetail.<exception>` through the `MessageSource`, so no code
   path hard-codes an English title (NFR-9, backend half).
6. **Logs never carry request content.** `spring.mvc.log-request-details`
   stays false and no request-logging filter is registered, so bodies and
   header values are never logged. Spring Security's DEBUG line
   ("Securing POST /path?query") includes the query string, so `application.yaml` pins `org.springframework.web` and
   `org.springframework.security` at INFO. The NFR-3 test runs twice: at
   TRACE with the sentinel in body and header, and at the configured levels
   with the sentinel in body, query and header. Later slices add their
   endpoints to the second case. A real controller logs the bound body at
   DEBUG ("Read … to …"), so that case is the one that must stay green.

## Module + hexagon placement

| Type | Module / layer |
|---|---|
| `SecurityConfiguration` | `cz.doucko.shared.adapter.in.web` |
| `SharedModule` (`@ApplicationModule`, OPEN) | `cz.doucko.shared` |
| `messages.properties` | resources |
| `tools/*.mjs` | outside the app (operator tooling) |

## Data model

None. `compose.yaml` declares the volume `doucko-pgdata`.

## Error handling

An undeclared path is 404 problem+json with the title from
`messages.properties`; no stack trace and no exception class appear in the
response (`server.error.include-*`). `/api/admin/**` is 403 until slice 2.

## Risks & mitigations

- Docker must run for the IT suite and the backup round trip. Without it
  the battery stops red; it never skips silently.
- `../frontend` must exist for `docker compose build`. `stack.mjs` checks for
  it and names the missing path.
