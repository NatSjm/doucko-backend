# Design — fix-restore-schema-drift

## Goals / Non-goals

Goals: after `npm run restore` the stack is either usable again or the operator is told exactly what
to do, with a non-zero exit; the output names the files the restore does not cover. Non-goals:
backing up or restoring the `doucko-storage` volume (NFR-17 files), downgrading a schema, changing
how Flyway runs inside the backend.

## Key decisions

1. **Restart, not only an instruction.** When the restored schema is behind this checkout's newest
   migration and the backend container is running, the tool runs `docker compose restart backend`
   and waits for its healthcheck. Restarting is the safer option: the restore already replaced the
   data under the running backend (confirmed with `--yes`), and until the restart every request that
   touches a newer table fails. The restart changes nothing beyond what a normal start does: Flyway
   applies the append-only migrations the restored schema lacks. Only the backend service is
   restarted; nothing is recreated and no volume is touched.
2. **Verify, do not assume.** After the healthcheck passes the tool reads `flyway_schema_history`
   again. A schema still behind means the running image is older than the checkout; the tool says to
   rebuild with `npm run stack:up` and exits 1.
3. **Fail loud, data stays restored.** Every failure after the restore committed (unreadable
   version, compose cannot list services, restart or healthcheck fails, schema still behind) prints
   that the data IS restored plus the manual `docker compose [-p <project>] restart backend` step,
   and exits 1. Failures before the commit keep their existing "nothing was changed" messages.
4. **Compare as Flyway does.** Versions are dotted numbers (`V1_2__x.sql` is 1.2) compared part by
   part, so V10 is newer than V9. Only successful, versioned history rows count. A database without
   `flyway_schema_history` counts as behind any migration.
5. **Newer dump: warn only.** A dump newer than the checkout (taken by a later version) is restored as
   asked; the tool warns that a backend from this checkout does not know the schema and restarts
   nothing (Flyway's default ignores future migrations, so a restart would not help).
6. **The checkout is the reference.** The migrations come from this checkout's
   `src/main/resources/db/migration`, the same tree `npm run stack:up` builds the image from.

## Placement

Operator tooling only (`tools/`), plain Node without dependencies like the other tools; no Kotlin
module or hexagon layer changes. `tools/lib/migrations.mjs` holds the pure version logic.

## Testing

`BackupRestoreIT` builds the backend image from this checkout under its own Compose project
(`doucko-it-<id>`, removed with `down -v --rmi local`), so the owner's `doucko` stack, volumes and image
are never touched. The migration scenario reproduces the smoke: a dump without V4, the backend running
at V4, restore, then the backend is healthy, V4 is applied again and `GET /api/child/hq` answers.

## Risks & mitigations

- Restart window: the backend is down for a Spring Boot start (seconds). Acceptable for a home stack
  during an operator restore.
- A crashing backend restarts in a loop (`restart: unless-stopped`): the wait stops at the first
  `restarting`/`exited` state or after 5 minutes and prints the manual step.
