# platform-foundation Specification

## Purpose
The backend's platform foundation: what every later module relies on. The Compose stack publishes
only the frontend, on loopback or a private LAN address, and the backend refuses any Host but its
own names (DNS rebinding); the data lives on a named volume with a tested backup and restore; every
undeclared path and refusal is problem+json from the message catalog; no request content ever
reaches the logs; and every fixture has recorded self-authored provenance. Product behaviour lives in
the docs store (`doucko`); this spec covers this repository's part.

## Requirements

### Requirement: Loopback or LAN binding only

The Compose stack SHALL publish only the frontend port, on a single
configurable bind address that defaults to `127.0.0.1`, and SHALL NOT publish
the backend or database port (NFR-2; store spec admin-access, "Local network only"). The
stack launcher SHALL refuse a bind address that is not loopback or a
canonical RFC 1918 IPv4 address, and SHALL check the bindings compose
actually renders (`docker compose config`), not its own reading of `.env`;
it starts exactly the configuration it checked (`.env`, no `--env-file`).
The guard runs in `npm run stack:up`; a plain `docker compose up` does not
run it. Only the actuator health endpoint SHALL be exposed; the actuator root
(`/actuator`) SHALL NOT list endpoints.

#### Scenario: Default binding is loopback

- **GIVEN** `compose.yaml` with no overrides
- **WHEN** its published ports are read
- **THEN** every published port binds `${DOUCKO_BIND_ADDRESS:-127.0.0.1}`, the frontend publishes one, and the backend and database services publish none

#### Scenario: Public bind address refused

- **WHEN** the launcher checks the bind address `0.0.0.0`, `::`, `8.8.8.8` or `192.168.1.300`
- **THEN** it exits non-zero naming the address, and it accepts `127.0.0.1`, `10.0.0.5`, `172.16.4.2` and `192.168.1.20`
- **AND** it refuses leading-zero octets (`010.0.0.5`), IPv4-mapped IPv6 and surrounding whitespace

#### Scenario: The effective binding is checked

- **GIVEN** an env file whose `export DOUCKO_BIND_ADDRESS=0.0.0.0` line follows `DOUCKO_BIND_ADDRESS=127.0.0.1`
- **WHEN** the launcher checks the configuration
- **THEN** it refuses, naming `0.0.0.0`, because that is the address compose would publish

#### Scenario: Only health is exposed

- **WHEN** `/actuator`, `/actuator/modulith`, `/actuator/info` or `/actuator/env` is requested
- **THEN** the answer is 404 (problem+json for `/actuator`), and `/actuator/health` answers 200

### Requirement: DNS rebinding refused

The backend SHALL answer only requests whose Host is `localhost`, a bare
IPv4 address or the Compose service name `backend`; any other Host SHALL get
400 problem+json titled from the catalog (NFR-2). A web page on a domain that
an attacker points at the laptop then cannot script the unauthenticated child
API. The frontend's nginx applies the same rule (421) in front of it.

#### Scenario: Allowed hosts

- **WHEN** an undeclared path is requested with Host `localhost`, `127.0.0.1`, `192.168.1.20:8081` or `backend:8080`
- **THEN** the answer is the ordinary 404 problem+json

#### Scenario: Rebound name refused

- **WHEN** a request carries Host `evil.example`, `127.0.0.1.evil.example`, `localhost.evil.example` or `backend.evil.example`
- **THEN** the answer is 400 problem+json titled "Bad request"

### Requirement: Data volume and backup

Postgres data SHALL live on the named Docker volume `doucko-pgdata`. A
documented `npm run backup` SHALL write a `pg_dump` of the database to a
file outside every repository (also when reached through a symlink or
junction), readable only by its owner (POSIX: directory 0700, file 0600; on
Windows: only under the user profile), and `npm run restore -- --yes <file>`
SHALL restore it, on Windows as well as on other platforms (NFR-10). Without
`--yes` restore changes nothing. Before dropping anything it SHALL check the
archive and take a safety backup, and it SHALL rebuild the schema from the
dump in a single transaction, so a failing dump leaves the data as it was and
no object from a newer schema survives an older dump. After the restore
committed it SHALL compare the newest applied Flyway version of the restored
schema with the newer of this checkout's newest migration and the version
the database had before the restore: when the restored schema is older and
the backend is running, it SHALL restart the backend, wait until it is healthy
and confirm the schema reached that version; when the backend is not running
it SHALL say that the next start migrates the database; when the restored
schema is newer it SHALL warn and restart nothing; a dump with tables but no
Flyway history SHALL get a warning and no restart. Whenever this check cannot
complete (version or backend state unreadable, restart or healthcheck failed,
schema still behind) it SHALL say that the data is restored, print the manual
step for the same compose file and project, and exit non-zero. Every restore
SHALL say that files on the `doucko-storage` volume are not restored.

#### Scenario: Backup survives losing the volume

- **GIVEN** a running database with one known row
- **WHEN** a backup is taken, the volume is removed, the stack is started again and the backup is restored
- **THEN** the known row is present

#### Scenario: A broken dump changes nothing

- **GIVEN** a running database with a known row and a truncated dump
- **WHEN** `restore --yes` is run with the truncated dump
- **THEN** it exits non-zero, the known row is still present, and a safety backup was written first

#### Scenario: An older dump over a newer schema

- **GIVEN** a dump taken before a table and a column were added
- **WHEN** it is restored
- **THEN** the dump's rows are back and the newer table and column are gone

#### Scenario: Unsafe backup targets

- **WHEN** the backup directory is inside a git working tree, directly or through a link, or (on Windows) outside the user profile
- **THEN** the backup is refused before anything is written

#### Scenario: A dump from before the newest migration under a running backend

- **GIVEN** the backend running at the newest migration and a dump taken before it
- **WHEN** `restore --yes` is run with that dump
- **THEN** the backend is restarted, the newest migration is applied again, the backend is healthy and serves the child area, and the output says that the `doucko-storage` files were not restored

#### Scenario: The restarted backend cannot migrate the restored schema

- **GIVEN** the backend running at the newest migration and a dump whose Flyway history ends before it while the newer tables already exist
- **WHEN** `restore --yes` is run with that dump
- **THEN** it exits non-zero, says that the data is restored and prints the `restart backend` and `logs backend` commands for the same compose project

#### Scenario: Restored schema and checkout disagree while no backend runs

- **GIVEN** no backend running
- **WHEN** an older dump is restored, or a dump newer than the checkout's migrations is restored
- **THEN** the restore says the next start migrates the database, or warns that the dump is newer, and restarts nothing

### Requirement: Undeclared paths answer problem+json

A request to a path the contract does not declare SHALL be answered 404 with
an `application/problem+json` body whose title comes from the backend
message catalog, with no stack trace or exception class (contract `info`,
Conventions; NFR-9), whatever the method or `Accept` header and without a
CSRF token outside the admin area; a direct request to `/error` is such a
path. A URL the security firewall rejects SHALL be answered 400 problem+json.
Until admin-access is implemented, every `/api/admin/**` request SHALL be
refused with 403 problem+json titled from the catalog.

#### Scenario: Unknown child path

- **WHEN** `GET /api/child/does-not-exist`, or a `POST`, `PUT` or `DELETE` to an undeclared path, is sent
- **THEN** the answer is 404 problem+json with the catalog title and no `trace` or `exception` member

#### Scenario: Admin area closed before admin-access

- **WHEN** any request to `/api/admin/**` is sent
- **THEN** it is refused with 403 problem+json and no handler runs, for GET, POST, PUT and DELETE

#### Scenario: Container-level errors

- **WHEN** `/error` is requested directly, an undeclared path is requested with `Accept: text/html`, or a URL with `//`, `;` or an encoded `..` is sent to a running server
- **THEN** the answer is problem+json (404, or 400 for the rejected URL) titled from the catalog, never Boot's error JSON or HTML page

### Requirement: Request content never logged

No request body, query-string or header value SHALL appear in the backend's
logs at any log level (NFR-3), including a body an endpoint binds, and startup
SHALL log no credential. The loggers that print request or bind content below
INFO (Spring web and security, Tomcat, Hibernate/JDBC bind values) are capped
at INFO by the logging configuration whatever `logging.level.*` says, and
Tomcat's HTTP/1.1 parser loggers, which print a malformed request line at
INFO, are capped at WARN.

#### Scenario: Nothing leaks even at TRACE

- **GIVEN** root, web, security and application logging at TRACE
- **WHEN** a request to an endpoint that binds its body, and one to an undeclared path, carry a sentinel string in the body, a query parameter and a header
- **THEN** the captured log output does not contain the sentinel

#### Scenario: Nothing leaks at the configured levels

- **GIVEN** the log levels from `application.yaml`
- **WHEN** a request to an endpoint that binds its body carries a sentinel string in the body, a query parameter and a header
- **THEN** the captured log output does not contain the sentinel

#### Scenario: The capture can see a leak

- **GIVEN** root, web and security logging at DEBUG
- **WHEN** application code, a Spring web logger and a Spring security logger each log a sentinel at DEBUG
- **THEN** the capture contains the application's line and neither Spring line (negative control)

#### Scenario: Malformed request line

- **WHEN** a request line whose target holds an invalid character carries a sentinel in its query
- **THEN** the answer is 400 and the captured log output does not contain the sentinel

#### Scenario: No generated password

- **WHEN** the application starts
- **THEN** its log contains no generated security password

### Requirement: Fixture provenance

Every fixture file in the repository SHALL be listed in
`fixtures/PROVENANCE.md` with origin `self-authored` and an author, and a
test SHALL fail on any unlisted file, any other origin, any listed file that
does not exist or is not a fixture path, and any malformed manifest row
(NFR-4, CA-D21). In scope: every file under `src/` except Kotlin sources,
everything under `fixtures/`, and every image, document, audio or video
file anywhere in git, `docs/qa/` included except the recordings
(`results[].video` .webm, `results[].screenshot` .png) that a
`docs/qa/**/manifest.json` lists. File names are read exactly (no quoting of
non-ASCII names). Exempt: `application.yaml`, `logback-spring.xml`, the
message catalog, and Flyway migrations with no data statement (`INSERT
INTO`, `COPY`, `UPDATE … SET`, `MERGE INTO`) anywhere outside comments.

#### Scenario: Unlisted fixture fails the check

- **GIVEN** a file under a fixture root that the manifest does not list
- **WHEN** the provenance test runs
- **THEN** it fails and names the file

### Requirement: Isolated end-to-end stack

The backend repository SHALL provide `node tools/e2e-stack.mjs up|down|seed|ps` that runs the
Compose stack as the separate project `doucko-e2e` with its own volumes, publishing only the
frontend on `127.0.0.1:18082` (NFR-2), configured only from committed, clearly fake test values
and never from `.env` or the caller's environment (NFR-3), and building the frontend from
`DOUCKO_FRONTEND_DIR` (default `../frontend`). `down` SHALL remove that project's containers and volumes and
SHALL NOT address any other Compose project. `seed` SHALL, through the public admin API,
create what is missing of one generated blank-page PDF source, published self-authored tasks of
at least two answer types and one published mission (NFR-4), and running it again SHALL leave
the same state; a state it did not create (duplicates, a different mission at its position) it
SHALL refuse instead of adopting.

#### Scenario: Down never reaches the owner's stack

- **GIVEN** the environment sets `COMPOSE_PROJECT_NAME=doucko`
- **WHEN** `node tools/e2e-stack.mjs down --dry-run` is run
- **THEN** the docker arguments name project `doucko-e2e` and no other project

#### Scenario: Seed is idempotent

- **GIVEN** a running e2e stack that was seeded once
- **WHEN** `seed` runs again
- **THEN** the admin API lists exactly one seed source, the same three published tasks and one published mission
