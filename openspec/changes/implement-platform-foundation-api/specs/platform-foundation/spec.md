## ADDED Requirements

### Requirement: Loopback or LAN binding only

The Compose stack SHALL publish every host port on a single configurable
bind address that defaults to `127.0.0.1`, and SHALL NOT publish the
database port (NFR-2; store spec admin-access, "Local network only"). The
stack launcher SHALL refuse a bind address that is not loopback or a
canonical RFC 1918 IPv4 address, and SHALL check the bindings compose
actually renders (`docker compose config`), not its own reading of `.env`.
Only the actuator health endpoint SHALL be exposed.

#### Scenario: Default binding is loopback

- **GIVEN** `compose.yaml` with no overrides
- **WHEN** its published ports are read
- **THEN** every published port binds `${DOUCKO_BIND_ADDRESS:-127.0.0.1}` and the database service publishes none

#### Scenario: Public bind address refused

- **WHEN** the launcher checks the bind address `0.0.0.0`, `::`, `8.8.8.8` or `192.168.1.300`
- **THEN** it exits non-zero naming the address, and it accepts `127.0.0.1`, `10.0.0.5`, `172.16.4.2` and `192.168.1.20`
- **AND** it refuses leading-zero octets (`010.0.0.5`), IPv4-mapped IPv6 and surrounding whitespace

#### Scenario: The effective binding is checked

- **GIVEN** an env file whose `export DOUCKO_BIND_ADDRESS=0.0.0.0` line follows `DOUCKO_BIND_ADDRESS=127.0.0.1`
- **WHEN** the launcher checks the configuration
- **THEN** it refuses, naming `0.0.0.0`, because that is the address compose would publish

#### Scenario: Only health is exposed

- **WHEN** `/actuator/modulith`, `/actuator/info` or `/actuator/env` is requested
- **THEN** the answer is 404, and `/actuator/health` answers 200

### Requirement: Data volume and backup

Postgres data SHALL live on the named Docker volume `doucko-pgdata`. A
documented `npm run backup` SHALL write a `pg_dump` of the database to a
file outside every repository, readable only by its owner, and
`npm run restore -- --yes <file>` SHALL restore it, on Windows as well as on
other platforms (NFR-10). Without `--yes` restore changes nothing. Before
dropping anything it SHALL check the archive and take a safety backup, and it
SHALL restore in a single transaction so a failing dump leaves the data as it was.

#### Scenario: Backup survives losing the volume

- **GIVEN** a running database with one known row
- **WHEN** a backup is taken, the volume is removed, the stack is started again and the backup is restored
- **THEN** the known row is present

#### Scenario: A broken dump changes nothing

- **GIVEN** a running database with a known row and a truncated dump
- **WHEN** `restore --yes` is run with the truncated dump
- **THEN** it exits non-zero, the known row is still present, and a safety backup was written first

### Requirement: Undeclared paths answer problem+json

A request to a path the contract does not declare SHALL be answered 404 with
an `application/problem+json` body whose title comes from the backend
message catalog, with no stack trace or exception class (contract `info`,
Conventions; NFR-9), whatever the method and without a CSRF token outside the
admin area. Until admin-access is implemented, every `/api/admin/**` request
SHALL be refused with 403 problem+json titled from the catalog.

#### Scenario: Unknown child path

- **WHEN** `GET /api/child/does-not-exist`, or a `POST`, `PUT` or `DELETE` to an undeclared path, is sent
- **THEN** the answer is 404 problem+json with the catalog title and no `trace` or `exception` member

#### Scenario: Admin area closed before admin-access

- **WHEN** any request to `/api/admin/**` is sent
- **THEN** it is refused with 403 problem+json and no handler runs, for GET, POST, PUT and DELETE

### Requirement: Request content never logged

No request body, query-string or header value SHALL appear in the backend's
logs at the log levels the application configures (NFR-3), including a body an
endpoint binds. Header values and bodies no endpoint binds SHALL stay out even
at TRACE. Spring Security's DEBUG request line includes the query string and
Spring MVC's DEBUG line `Read … to …` includes a bound body, so
`org.springframework.security` and `org.springframework.web` stay at INFO in
`application.yaml`; raising them is never a supported configuration.

#### Scenario: Unbound body and header stay out even at TRACE

- **GIVEN** web, security and application logging at TRACE
- **WHEN** a request to a path no endpoint binds carries a sentinel string in its body and in a header
- **THEN** the captured log output does not contain the sentinel

#### Scenario: Nothing leaks at the configured levels

- **GIVEN** the log levels from `application.yaml`
- **WHEN** a request to an endpoint that binds its body carries a sentinel string in the body, a query parameter and a header
- **THEN** the captured log output does not contain the sentinel

#### Scenario: The capture can see a leak

- **GIVEN** `org.springframework.web` at DEBUG
- **WHEN** the same request is sent to the binding endpoint
- **THEN** the captured log output contains the sentinel (negative control)

### Requirement: Fixture provenance

Every fixture file in the repository SHALL be listed in
`fixtures/PROVENANCE.md` with origin `self-authored` and an author, and a
test SHALL fail on any unlisted file, any other origin, any listed file that
does not exist or lies outside a fixture root, and any malformed manifest row
(NFR-4, CA-D21). Fixture roots are `src/test/resources/`,
`src/main/resources/`, `fixtures/` and non-Kotlin files under
`src/test/kotlin/`; only application config, the message catalog and Flyway
migrations are exempt.

#### Scenario: Unlisted fixture fails the check

- **GIVEN** a file under a fixture root that the manifest does not list
- **WHEN** the provenance test runs
- **THEN** it fails and names the file
