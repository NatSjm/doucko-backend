## 1. Contract and schema

- [x] 1.1 Confirm the `contract/` pin (docs `4c87d67`, API 0.5.1) generates and compiles.
- [x] 1.2 No Flyway migration in this change (first table: slice 4).

## 2. Failing tests first (red)

- [x] 2.1 `ComposeConfigurationTest` (NFR-2, NFR-10): every published port uses the bind variable with default `127.0.0.1`; the database publishes no port; Postgres data is on the named volume `doucko-pgdata`.
- [x] 2.2 `BindAddressTest` (NFR-2): `tools/stack.mjs --check-bind <addr>` accepts loopback and RFC 1918, refuses `0.0.0.0`, `::`, public and malformed addresses.
- [x] 2.3 `UndeclaredPathTest` (contract convention, NFR-9): an undeclared path is 404 problem+json whose title comes from `messages.properties`; `/api/admin/**` is refused.
- [x] 2.4 `RequestContentNotLoggedTest` (NFR-3): a sentinel in body and header never reaches the logs even at TRACE; body, query and header stay out at the configured levels.
- [x] 2.5 `FixtureProvenanceTest` (NFR-4, CA-D21): every fixture file is listed in `fixtures/PROVENANCE.md` as `self-authored` with an author, and every listed file exists.
- [x] 2.6 `ContractGenerationTest` (NFR-12): the generated `*Api` interfaces of every contract tag are on the classpath, generated from the pinned `contract/api/openapi.yaml`.
- [x] 2.7 `ModularityTest` traced to NFR-11.
- [x] 2.8 `BackupRestoreIT` (NFR-10, Docker): write a row, back up, drop the volume, restore, read the row back.
- [x] 2.9 Run the tests and confirm RED on assertions, not on compile errors. Recorded after the fact by mutation (one targeted mutation per guarded behaviour), plus test-first RED for both review rounds: `docs/qa/evidence/slice1-review-fixes.md`.

## 3. Implementation (green)

- [x] 3.1 `compose.yaml`, `Dockerfile`, `.dockerignore`.
- [x] 3.2 `tools/stack.mjs`, `tools/backup.mjs`, `tools/restore.mjs`; npm scripts.
- [x] 3.3 `cz.doucko.shared`: `SharedModule`, `SecurityConfiguration`; `messages.properties`; logging settings.
- [x] 3.4 `fixtures/PROVENANCE.md`.

## 4. Validation, review, archive

- [x] 4.1 `npm run qa:verify` green (needs Docker).
- [x] 4.2 `npx openspec validate implement-platform-foundation-api --strict`.
- [ ] 4.3 review-gate workflow; fix confirmed findings; `review-findings.json` (clean: true); re-run 4.1.
- [x] 4.4 Smoke: `npm run stack:up`; `docker compose ps` shows only `127.0.0.1` bindings and no db port; `curl http://127.0.0.1:8080/actuator/health` → UP; `curl -i http://127.0.0.1:8080/api/child/nope` → 404 problem+json; `npm run backup`; `npm run stack:down`. Evidence: `docs/qa/evidence/slice1-compose-smoke.md`.
  - 2026-10-04: re-run after the backend port stopped being published (DNS rebinding): API through the frontend on 8081 (404 problem+json, 403 admin, 421 foreign host), backend firewall 400 in-network, health via the in-container check, backup written. Same evidence file.
- [ ] 4.5 Update `docs/current-state.md`.
- [ ] 4.6 Archive; commit with `Slice: implement-platform-foundation-api` + `Refs: NFR-2, NFR-3, NFR-4, NFR-9, NFR-10, NFR-11, NFR-12`.
