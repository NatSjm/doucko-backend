// NFR-10: restore a dump written by `npm run backup` into the running database.
//
//   npm run restore -- --yes <file>
//   node tools/restore.mjs --yes [--project <compose project>] [--backup-dir <dir>] <file>
//
// Restoring replaces the whole database schema, so it needs --yes. Before anything is dropped it
// (1) checks that the file is a readable pg_dump archive and (2) takes a safety backup of the
// current data (default ~/doucko-backups). Inside the db container the archive is then converted to
// SQL (a truncated dump fails here, before anything changed), and `public` is dropped, recreated and
// replayed in ONE psql transaction: objects from a newer schema do not survive an older dump, and
// any error rolls everything back.
//
// Flyway migrates only when the backend starts. When the restored `flyway_schema_history` ends
// before this checkout's newest migration and the backend is running, the backend is restarted and
// the restore waits until it is healthy and has migrated the schema; otherwise the running backend
// would keep serving a schema that lacks the newer tables (child and admin requests fail). Restarting
// changes no data beyond what a normal start does, so it is safer than leaving the stack broken
// until someone notices. If the restart fails, the restore prints the manual step and exits 1 — the
// data is restored either way. Files on the doucko-storage volume are not in the dump and are never
// restored; the output says so.
import { spawnSync } from "node:child_process";
import { closeSync, openSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { composeArgs, parseArgs } from "./lib/compose.mjs";
import { compareVersions, latestMigration, maxVersion } from "./lib/migrations.mjs";

const { opts, positional } = parseArgs(process.argv.slice(2), ["project", "backup-dir"]);
const file = positional[0];

if (!file || !statSync(file, { throwIfNoEntry: false })?.isFile()) {
  console.error("usage: node tools/restore.mjs --yes [--project <name>] [--backup-dir <dir>] <backup file>");
  process.exit(2);
}
if (!opts.yes) {
  console.error(`restore: this replaces the current database with ${file}. Re-run with --yes to proceed.`);
  process.exit(1);
}

const compose = (...args) => spawnSync("docker", [...composeArgs(opts.project), ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "inherit"] });

/** Run a command inside the db container with the dump file on stdin. */
function inDb(...command) {
  const fd = openSync(file, "r");
  try {
    return spawnSync("docker", [...composeArgs(opts.project), "exec", "-T", "db", ...command], { stdio: [fd, "ignore", "inherit"] });
  } finally {
    closeSync(fd);
  }
}

if (inDb("pg_restore", "--list").status !== 0) {
  console.error(`restore: ${file} is not a readable pg_dump archive — nothing was changed.`);
  process.exit(1);
}

const backup = join(dirname(fileURLToPath(import.meta.url)), "backup.mjs");
const safety = spawnSync(
  process.execPath,
  [backup, ...(opts.project ? ["--project", opts.project] : []), ...(opts["backup-dir"] ? ["--out", opts["backup-dir"]] : [])],
  { stdio: "inherit" },
);
if (safety.status !== 0) {
  console.error("restore: the safety backup of the current data failed — nothing was changed.");
  process.exit(1);
}

const RESTORE = [
  "set -e",
  "sql=$(mktemp)",
  `trap 'rm -f "$sql"' EXIT`,
  `pg_restore --no-owner --file "$sql"`,
  `{ echo "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"; cat "$sql"; } | psql -U doucko -d doucko -q -v ON_ERROR_STOP=1 --single-transaction`,
].join("\n");
const result = inDb("sh", "-c", RESTORE);
if (result.status !== 0) {
  console.error(`restore: failed (exit ${result.status ?? result.error?.message}); nothing was committed and the data is unchanged.`);
  process.exit(1);
}
console.log(`restore complete: ${file}`);
console.log(
  "restore: files on the doucko-storage volume (uploaded PDFs and task images) are not in the dump and were not restored — " +
    "files uploaded since the dump stay on the volume, files deleted since stay missing.",
);

// ---------- the restored schema against this checkout's migrations ----------

/** Rows of a psql query in the db container, or null when psql failed. */
function query(sql) {
  const r = compose("exec", "-T", "db", "psql", "-U", "doucko", "-d", "doucko", "-tAc", sql);
  return r.status === 0 ? r.stdout.split(/\r?\n/).map((s) => s.trim()).filter(Boolean) : null;
}

/** The newest successfully applied Flyway version: a string, null for a never-migrated database, undefined if unreadable. */
function schemaVersion() {
  const exists = query("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL");
  if (exists === null) return undefined;
  if (exists[0] !== "t") return null;
  const rows = query("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL");
  return rows === null ? undefined : maxVersion(rows);
}

/** Running, stopped, or undefined when compose could not say. */
function backendRunning() {
  const r = compose("ps", "--status", "running", "--services");
  return r.status === 0 ? r.stdout.split(/\r?\n/).some((s) => s.trim() === "backend") : undefined;
}

const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

/** Waits for the backend container's healthcheck; false when it turns unhealthy, stops or times out. */
function backendHealthy(timeoutMs = 300_000) {
  for (const deadline = Date.now() + timeoutMs; Date.now() < deadline; sleep(2_000)) {
    const r = compose("ps", "--all", "--format", "json", "backend");
    if (r.status !== 0) continue;
    // One JSON object per line (compose v2.21+), or one array (older releases).
    const containers = r.stdout
      .trim()
      .split(/\r?\n/)
      .filter(Boolean)
      .flatMap((line) => JSON.parse(line));
    const c = containers[0];
    if (!c) return false;
    if (c.Health === "healthy") return true;
    if (c.Health === "unhealthy" || ["exited", "dead", "restarting"].includes(c.State)) return false;
  }
  return false;
}

const manualRestart = `docker compose${opts.project ? ` -p ${opts.project}` : ""} restart backend`;
function fail(message) {
  console.error(`restore: ${message}`);
  process.exit(1);
}

const latest = latestMigration();
const restored = schemaVersion();
const label = (v) => (v === null ? "no Flyway migration" : v === undefined ? "an unreadable version" : `V${v}`);

if (restored === undefined) {
  fail(`the data is restored, but its Flyway schema version could not be read. If the backend is running, run \`${manualRestart}\` so Flyway migrates it.`);
}
const behind = latest !== null && (restored === null || compareVersions(restored, latest) < 0);
if (latest !== null && restored !== null && compareVersions(restored, latest) > 0) {
  console.warn(
    `restore: WARNING — the dump's schema (V${restored}) is newer than this checkout's newest migration (V${latest}). ` +
      "A backend built from this checkout does not know it; use the version the dump was taken with. Nothing was restarted.",
  );
} else if (behind) {
  const running = backendRunning();
  const gap = `the dump's schema is at ${label(restored)}, this checkout's migrations end at V${latest}`;
  if (running === undefined) {
    fail(`the data is restored, but ${gap} and docker compose could not say whether the backend runs. If it does, run \`${manualRestart}\`.`);
  } else if (!running) {
    console.log(`restore: ${gap}; the backend is not running, so Flyway migrates the database when it next starts (npm run stack:up).`);
  } else {
    console.log(`restore: ${gap}; the running backend migrates only at startup, so it is restarted now.`);
    const restart = spawnSync("docker", [...composeArgs(opts.project), "restart", "backend"], { stdio: ["ignore", "inherit", "inherit"] });
    if (restart.status !== 0 || !backendHealthy()) {
      fail(
        `the data is restored, but the backend did not come back healthy after the restart. ` +
          `Check \`docker compose logs backend\`, fix the cause and run \`${manualRestart}\`.`,
      );
    }
    const migrated = schemaVersion();
    if (!migrated || compareVersions(migrated, latest) < 0) {
      fail(
        `the backend restarted, but the schema is at ${label(migrated)}, not V${latest}: its image is older than this ` +
          "checkout. Rebuild and start it with npm run stack:up.",
      );
    }
    console.log(`restore: backend restarted; Flyway migrated the restored schema to V${migrated}.`);
  }
}
