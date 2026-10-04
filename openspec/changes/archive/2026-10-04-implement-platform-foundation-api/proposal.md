# Implement platform foundation (backend)

## Why

Slice 1 of the capability plan (`contract/docs/mvp-capability-plan.md` §4.1
and §2.1). The backend owns the mechanisms for the cross-cutting NFRs that
every later slice must keep green: LAN-only binding (NFR-2), PII-free logs
(NFR-3), fixture provenance (NFR-4, CA-D21), externalized backend messages
(NFR-9), the Docker volume and backup (NFR-10), module boundaries (NFR-11),
and contract-generated interfaces (NFR-12). Store specs: `admin-access`
("Local network only") and `content-authoring` ("Copyright-safe content",
CA-D21). No FR is owned by this change; FR-58 is frontend-only.

## What Changes

- `compose.yaml`: Postgres 16 on the named volume `doucko-pgdata`, not
  published to the host; backend and frontend (nginx, built from
  `../frontend`) published on `${DOUCKO_BIND_ADDRESS:-127.0.0.1}` only.
  `Dockerfile` for the backend jar.
- `tools/`: `stack.mjs` (refuses a bind address that is not loopback or
  RFC 1918), `backup.mjs` / `restore.mjs` (`pg_dump` / `pg_restore` through
  `docker compose exec`, Windows-safe); npm scripts `stack:up`, `stack:down`,
  `backup`, `restore`.
- Module `cz.doucko.shared`: baseline `SecurityFilterChain` (child API open
  without CSRF, `/api/admin/**` closed until admin-access), and
  `messages.properties` for problem titles (NFR-9).
- Logging policy: no request bodies, parameters or headers in logs at any
  level (NFR-3).
- `fixtures/PROVENANCE.md` + a check over every fixture file (CA-D21).
- Tests for each mechanism, traced to its NFR.

No endpoint of `api/openapi.yaml` is implemented yet. No Flyway migration:
the first table arrives with child-profile (slice 4). The contract convention
"undeclared path → 404 problem+json" is implemented here.

## Impact

- Store specs: admin-access, content-authoring · Contract pin: docs `4c87d67` (API 0.5.1)
- Modules: `cz.doucko.shared` (new) · Flyway: none
- Dependencies: none on earlier slices; no new Maven dependencies (SnakeYAML
  comes with Spring Boot)
- Decision D-P1 (checkpoint 2): Compose and backup live in this repo.
