## 1. Failing tests first (red)

- [x] 1.1 `BackupRestoreIT`: storage note on every restore; dump before the newest migration with the backend running → backend restarted, V4 applied again, child HQ answers; older dump without a backend → "not running" note; dump newer than the checkout → warning (NFR-10).
- [x] 1.2 `OperatorToolGuardTest`: Flyway version parsing and numeric comparison (`tools/lib/migrations.mjs`).
- [x] 1.3 Run and confirm RED on assertions. Evidence: `docs/qa/evidence/fix-restore-schema-drift.md`.

## 2. Implementation (green)

- [x] 2.1 `tools/lib/migrations.mjs`.
- [x] 2.2 `tools/restore.mjs`: storage note, version check, restart and health wait, manual step and exit 1 on failure.

## 3. Validation, review, archive

- [x] 3.1 `npm run qa:verify` green. Evidence: `docs/qa/automated-verification-latest.md`.
- [x] 3.2 `npx openspec validate fix-restore-schema-drift --strict`.
- [x] 3.3 Review (fresh reviewer); every finding dispositioned in `review-findings.json`.
- [x] 3.4 Update `docs/current-state.md` (gotcha replaced).
- [x] 3.5 Archive; commit with `Slice: fix-restore-schema-drift` + `Refs: NFR-10`.
