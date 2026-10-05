# Fix restore over a newer running schema (backend)

## Why

Found in the slice 7 real-stack smoke (2026-10-05, `docs/qa/evidence/slice7-compose-smoke.md`):
`npm run restore -- --yes <dump>` of a dump taken at V3 while the running backend was at V4 dropped
V4's tables under the running backend. Flyway migrates only at startup, so child and admin requests
failed with 500/400 until someone ran `docker compose restart backend`. The restore also said nothing
about the uploaded files, which it never restores. Backend-owned requirement: NFR-10 (store spec
`platform-foundation` here, requirement "Data volume and backup"). This is a defect fix in an
existing capability, not a new slice; no contract operation changes.

## What Changes

- After a successful restore, `tools/restore.mjs` reads the newest successful version in the restored
  `flyway_schema_history` and compares it with the newest `V<n>__*.sql` in
  `src/main/resources/db/migration`.
  - Older (or no history) and the backend container is running: restart the backend service, wait for
    its healthcheck, and confirm Flyway brought the schema to the newest version. If that fails, print
    the manual `docker compose restart backend` step and exit 1 (the data is restored either way).
  - Older and the backend is not running: say that its next start migrates the database; exit 0.
  - Newer than the checkout: warn, restart nothing; exit 0.
- Every successful restore says that files on the `doucko-storage` volume are not restored.
- New helper `tools/lib/migrations.mjs` (Flyway version parsing and numeric comparison).

## Impact

- Spec: `platform-foundation` — MODIFIED "Data volume and backup" (two scenarios added).
- Code: `tools/restore.mjs`, `tools/lib/migrations.mjs`; no `src/main` change, no migration.
- Tests: `BackupRestoreIT` (real backend built from this checkout in its own Compose project),
  `OperatorToolGuardTest`.
- Docs: `docs/current-state.md` gotcha replaced by the new behaviour.
