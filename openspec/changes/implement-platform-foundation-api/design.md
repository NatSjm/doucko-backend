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
   validate it, so `tools/stack.mjs` refuses to start unless every published
   port in the configuration compose renders (`docker compose config`) binds
   loopback or canonical RFC 1918 (`10/8`, `172.16/12`, `192.168/16`). It
   never re-parses `.env`: compose accepts `export`, lets a later duplicate
   win and lets the process environment override the file. `up` refuses
   `--env-file`, so it starts exactly what it checked. The database
   publishes no port (admin-access "Database not published"). Actuator
   exposes only `health` (the compose healthcheck).
3. **Backup through `docker compose exec`.** `pg_dump -Fc` streams to the
   host as binary, which avoids Windows newline and console code-page
   mangling. Output defaults to `~/doucko-backups/` (outside every repo,
   because the pre-commit hook forbids `backups/` paths). The target is
   checked as given and again on its real path, so a link cannot lead into a
   working tree. Owner-only: 0700 directory (also when it existed) and 0600
   file on POSIX; Windows ignores modes, so there the target must be under the
   user profile. Dumps are never overwritten. `restore` needs `--yes`,
   checks the archive with `pg_restore --list`, takes a safety backup, then,
   inside the db container, converts the archive to SQL (a truncated dump
   fails here) and replays `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`
   plus that SQL in one `psql --single-transaction` with `ON_ERROR_STOP`.
   `pg_restore --clean` alone would keep objects from a newer schema that the
   dump does not know, and Flyway would then fail on the next start.
4. **Baseline security without auth.** With Spring Security on the
   classpath and no configuration, every request needs HTTP Basic with a
   generated password. The baseline chain permits everything except
   `/api/admin/**` (`denyAll` until slice 2). CSRF applies only to
   `/api/admin/**`: nothing else has a session (contract `info`), and a
   CSRF refusal elsewhere would turn an undeclared write into a 403 instead
   of the contract's 404. Every response written outside MVC's exception
   handling goes through `ProblemResponses` (titles `problem.title.<status>`
   from `messages.properties`): the entry point and access-denied handler
   (403), a `RequestRejectedHandler` for URLs the firewall rejects (400),
   and `ProblemErrorController`, which replaces Boot's BasicErrorController:
   container-forwarded errors keep their status, a direct `/error` request
   is 404, and the content type is preset, so `Accept: text/html` still gets
   problem+json. An empty `UserDetailsService` stops Boot from minting a
   user and logging its password. Slice 2 replaces the admin rule and the
   user store.
5. **Problem titles from `messages.properties`.** Spring's
   `ResponseEntityExceptionHandler` resolves `problemDetail.title.<exception>`
   and `problemDetail.<exception>` through the `MessageSource`, so no code
   path hard-codes an English title (NFR-9, backend half).
6. **Logs never carry request content, at any level.** `spring.mvc.log-request-details`
   stays false and no request-logging filter is registered. Below INFO,
   Spring Security logs "Securing POST /path?query", Spring MVC logs a bound
   body ("Read … to …") and dumps handler arguments at TRACE, Tomcat logs raw
   request bytes and Hibernate/JDBC log bind values. `logback-spring.xml`
   installs `RequestContentLogGuard`, a turbo filter that denies events
   below INFO from those loggers (and Spring's "_org.springframework…" hidden
   loggers) whatever `logging.level.*` says. Debugging those frameworks at
   DEBUG is therefore not possible in this app; that is the price of NFR-3.
   The NFR-3 test sends the sentinel in body, query and header to a test
   endpoint that binds the body, at TRACE and at the configured levels. A
   negative control at DEBUG shows the capture sees an application line and
   the guard drops the Spring web and security ones. Each case sets the root
   level itself, because log levels are JVM-global across cached test contexts.

## Module + hexagon placement

| Type | Module / layer |
|---|---|
| `SecurityConfiguration`, `ProblemResponses`, `ProblemErrorController` | `cz.doucko.shared.adapter.in.web` |
| `RequestContentLogGuard` (+ `logback-spring.xml`) | `cz.doucko.shared.logging` |
| `SharedModule` (`@ApplicationModule`, OPEN) | `cz.doucko.shared` |
| `messages.properties` | resources |
| `tools/*.mjs` | outside the app (operator tooling) |

## Data model

None. `compose.yaml` declares the volume `doucko-pgdata`.

## Error handling

An undeclared path is 404 problem+json with the title from
`messages.properties`; no stack trace and no exception class appear in the
response (`server.error.include-*`). `/api/admin/**` is 403 problem+json until slice 2.

## Risks & mitigations

- A plain `docker compose up` bypasses the NFR-2 guard: compose cannot
  validate an interpolated address. The documented start is
  `npm run stack:up` (compose.yaml header, current-state); the default is
  still `127.0.0.1`. Accepted: making compose fail closed would need a
  validator service duplicating the address rules in shell.
- `ktlint`'s package-name rule is off under `adapter/in/` (the backticked
  `in`); `PackageLayoutTest` checks every package against its directory
  instead.

- Docker must run for the IT suite and the backup round trip. Without it
  the battery stops red; it never skips silently.
- `../frontend` must exist for `docker compose build`. `stack.mjs` checks for
  it and names the missing path.
