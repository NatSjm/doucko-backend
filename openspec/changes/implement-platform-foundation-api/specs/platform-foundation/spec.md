## ADDED Requirements

### Requirement: Loopback or LAN binding only

The Compose stack SHALL publish every host port on a single configurable
bind address that defaults to `127.0.0.1`, and SHALL NOT publish the
database port (NFR-2; store spec admin-access, "Local network only"). The
stack launcher SHALL refuse a bind address that is not loopback or an
RFC 1918 IPv4 address.

#### Scenario: Default binding is loopback

- **GIVEN** `compose.yaml` with no overrides
- **WHEN** its published ports are read
- **THEN** every published port binds `${DOUCKO_BIND_ADDRESS:-127.0.0.1}` and the database service publishes none

#### Scenario: Public bind address refused

- **WHEN** the launcher checks the bind address `0.0.0.0`, `::`, `8.8.8.8` or `192.168.1.300`
- **THEN** it exits non-zero naming the address, and it accepts `127.0.0.1`, `10.0.0.5`, `172.16.4.2` and `192.168.1.20`

### Requirement: Data volume and backup

Postgres data SHALL live on the named Docker volume `doucko-pgdata`. A
documented `npm run backup` SHALL write a `pg_dump` of the database to a
file outside every repository, and `npm run restore <file>` SHALL restore
it, on Windows as well as on other platforms (NFR-10).

#### Scenario: Backup survives losing the volume

- **GIVEN** a running database with one known row
- **WHEN** a backup is taken, the volume is removed, the stack is started again and the backup is restored
- **THEN** the known row is present

### Requirement: Undeclared paths answer problem+json

A request to a path the contract does not declare SHALL be answered 404 with
an `application/problem+json` body whose title comes from the backend
message catalog, with no stack trace or exception class (contract `info`,
Conventions; NFR-9). Until admin-access is implemented, every
`/api/admin/**` request SHALL be refused.

#### Scenario: Unknown child path

- **WHEN** `GET /api/child/does-not-exist` is sent
- **THEN** the answer is 404 problem+json with the catalog title and no `trace` or `exception` member

#### Scenario: Admin area closed before admin-access

- **WHEN** any request to `/api/admin/**` is sent
- **THEN** it is refused with 4xx and no handler runs

### Requirement: Request content never logged

No request body or header value SHALL appear in the backend's logs at any
log level, and no query-string value SHALL appear at the log levels the
application configures (NFR-3). Spring Security's DEBUG request line
includes the query string, so `org.springframework.security` and
`org.springframework.web` stay at INFO in `application.yaml`.

#### Scenario: Body and header stay out even at TRACE

- **GIVEN** web, security and application logging at TRACE
- **WHEN** a request carries a sentinel string in its body and in a header
- **THEN** the captured log output does not contain the sentinel

#### Scenario: Nothing leaks at the configured levels

- **GIVEN** the log levels from `application.yaml`
- **WHEN** a request carries a sentinel string in its body, a query parameter and a header
- **THEN** the captured log output does not contain the sentinel

### Requirement: Fixture provenance

Every fixture file in the repository SHALL be listed in
`fixtures/PROVENANCE.md` with origin `self-authored` and an author, and a
test SHALL fail on any unlisted file, any other origin, or any listed file
that does not exist (NFR-4, CA-D21).

#### Scenario: Unlisted fixture fails the check

- **GIVEN** a file under `src/test/resources/` or `fixtures/` that the manifest does not list
- **WHEN** the provenance test runs
- **THEN** it fails and names the file
