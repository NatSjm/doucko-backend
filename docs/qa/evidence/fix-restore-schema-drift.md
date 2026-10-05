# fix-restore-schema-drift — RED, GREEN and a replay of the slice 7 smoke gotcha

- **Date:** 2026-10-05 (Europe/Prague)
- **Defect:** `npm run restore` of a dump taken before the newest migration (V3 dump, backend at V4)
  dropped V4's tables under the running backend and exited 0; requests failed until
  `docker compose restart backend` (`docs/qa/evidence/slice7-compose-smoke.md`, "Clean-up and a restore
  gotcha"). The output did not mention that the `doucko-storage` files are not restored.
- **Isolation:** every run below used its own Compose project (`doucko-it-<id>`, `doucko-manual-fix`),
  removed afterwards with `down -v --rmi local`. The owner's `doucko` stack, volumes and image were not
  touched.

## RED (task 1.3)

Command: `node scripts/mvnw.mjs -B test-compile failsafe:integration-test failsafe:verify -Dit.test=BackupRestoreIT -Dspotless.check.skip=true`,
against the unchanged `tools/restore.mjs` (git `394ed08`).

`BackupRestoreIT`: 7 run, 4 failures, all on assertions; the 3 existing tests stayed green.

| Test | Failure |
|---|---|
| a backup survives losing the volume and restores the data | `expected:<doucko-storage>` — no storage note in the output |
| restoring a dump from before the newest migration restarts the running backend, which migrates it again | `expected:<restarted>` — the old tool exits 0 with V4's tables gone |
| restoring an older dump with no backend running says its next start migrates it | `expected:<V1>` — no version check |
| restoring a dump newer than this checkout warns and restarts nothing | `expected:<V999>` — no version check |

## GREEN

- Same command after the fix: `BackupRestoreIT` 7 run, 0 failures (204 s, including the backend image
  build in the throwaway project).
- `OperatorToolGuardTest` 5 run, 0 failures (incl. the new version-ordering test: V10 > V9, 1.10 > 1.9,
  `V1_2` = 1.2, the checkout's newest migration).

## Replay of the smoke scenario (old tool vs new tool)

Throwaway project `doucko-manual-fix`, backend built from this checkout: V4 dropped and its history row
deleted, dump taken (the "V3 dump"), backend restarted (V4 back), then the dump restored with each tool.

```text
--- before restore: practice_run tables = 1
--- old restore.mjs (git HEAD):
restore complete: ~/doucko-manual-fix-backups/dumps/doucko-20261005-172607-070.dump
exit 0
--- after old restore: practice_run tables = 0
--- new restore.mjs:
restore complete: ~/doucko-manual-fix-backups/dumps/doucko-20261005-172607-070.dump
restore: files on the doucko-storage volume (uploaded PDFs and task images) are not in the dump and were not restored — files uploaded since the dump stay on the volume, files deleted since stay missing.
restore: the dump's schema is at V3, this checkout's migrations end at V4; the running backend migrates only at startup, so it is restarted now.
 Container doucko-manual-fix-backend-1 Restarting
 Container doucko-manual-fix-backend-1 Started
restore: backend restarted; Flyway migrated the restored schema to V4.
exit 0
--- after new restore: practice_run tables = 1
```

(Safety-backup lines and psql `NOTICE` output trimmed.) `GET /api/child/hq` answered after both
restores: with an empty database the HQ does not read the V4 tables, so the replay shows the dropped
tables rather than the 500s the smoke saw with data in them.

## Not covered by a test

The failure branches after the restore committed (restart fails, healthcheck turns unhealthy or the
container crash-loops, schema still behind because the image is older than the checkout, version
unreadable) print the manual `docker compose restart backend` step and exit 1; they are reviewed, not
exercised by a test.
