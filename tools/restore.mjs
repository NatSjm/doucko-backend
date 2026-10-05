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
// Flyway migrates only when the backend starts. The restore expects the newest of this checkout's
// migrations and the version the database had before the restore (what the running backend applied).
// When the restored `flyway_schema_history` ends before that and the backend is running, the backend
// is restarted and the restore waits until it is healthy and has migrated the schema; otherwise the
// running backend would keep serving a schema that lacks the newer tables (child and admin requests
// fail). Restarting changes no data beyond what a normal start does, so it is safer than leaving the
// stack broken until someone notices. Every failure after the restore committed says that the data
// IS restored, prints the manual step and exits 1. Files on the doucko-storage volume are not in the
// dump and are never restored; the output says so.
import { spawnSync } from "node:child_process";
import { closeSync, openSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { COMPOSE_FILE, composeArgs, parseArgs } from "./lib/compose.mjs";
import { MIGRATIONS_DIR, compareVersions, latestMigration, maxVersion } from "./lib/migrations.mjs";

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
// What the operator is told to run: the same compose file and project this tool uses.
const composeHint = `docker compose -f "${COMPOSE_FILE}"${opts.project ? ` -p ${opts.project}` : ""}`;
const manualRestart = `${composeHint} restart backend`;

/** Run a command inside the db container with the dump file on stdin. */
function inDb(...command) {
  const fd = openSync(file, "r");
  try {
    return spawnSync("docker", [...composeArgs(opts.project), "exec", "-T", "db", ...command], { stdio: [fd, "ignore", "inherit"] });
  } finally {
    closeSync(fd);
  }
}

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

if (inDb("pg_restore", "--list").status !== 0) {
  console.error(`restore: ${file} is not a readable pg_dump archive — nothing was changed.`);
  process.exit(1);
}

// Read before anything changes: the checkout's newest migration, and the version the running backend
// applied (an image newer than the checkout needs its own migrations back as well).
let latest;
try {
  latest = latestMigration();
} catch (e) {
  console.error(`restore: cannot read the migrations in ${MIGRATIONS_DIR} (${e.message}) — nothing was changed.`);
  process.exit(1);
}
const before = schemaVersion();
const target = maxVersion([latest, before].filter((v) => typeof v === "string"));

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

// ---------- the restored schema against the migrations the backend expects ----------

/** Running, stopped, or undefined when compose could not say. */
function backendRunning() {
  const r = compose("ps", "--status", "running", "--services");
  return r.status === 0 ? r.stdout.split(/\r?\n/).some((s) => s.trim() === "backend") : undefined;
}

const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

/** The backend container as `docker compose ps` reports it, or null when that output is unusable. */
function backendContainer() {
  const r = compose("ps", "--all", "--format", "json", "backend");
  if (r.status !== 0) return null;
  try {
    // One JSON object per line (compose v2.21+), or one array (older releases).
    const containers = r.stdout
      .trim()
      .split(/\r?\n/)
      .filter(Boolean)
      .flatMap((line) => JSON.parse(line));
    return containers[0] ?? null;
  } catch {
    return null;
  }
}

/** How often the restart policy has restarted the container (crashes, not `docker compose restart`), or null. */
function restartCount(id) {
  const r = spawnSync("docker", ["inspect", "--format", "{{.RestartCount}}", id], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
  return r.status === 0 ? Number(r.stdout.trim()) : null;
}

/**
 * Waits for the backend container's healthcheck; false when it turns unhealthy, stops, crashes or
 * times out. A crash-looping container can look "running, starting" at every poll, so a growing
 * restart count counts as a crash too.
 */
function backendHealthy(timeoutMs = 300_000) {
  let crashes = null;
  for (const deadline = Date.now() + timeoutMs; Date.now() < deadline; sleep(2_000)) {
    const c = backendContainer();
    if (!c) continue;
    if (c.Health === "healthy") return true;
    if (c.Health === "unhealthy" || ["exited", "dead", "restarting"].includes(c.State)) return false;
    const count = restartCount(c.ID);
    if (crashes === null) crashes = count;
    else if (count !== null && count > crashes) return false;
  }
  return false;
}

/** After the commit every failure says the data is restored, names the manual step and exits 1. */
function fail(problem, step = `run \`${manualRestart}\``) {
  console.error(`restore: the data is restored, but ${problem}. Next: ${step}.`);
  process.exit(1);
}

const restored = schemaVersion();
const label = (v) => (v === null ? "no Flyway migration" : v === undefined ? "an unreadable version" : `V${v}`);
const expected =
  before && before === target && before !== latest
    ? `the schema before the restore was at V${target}`
    : `this checkout's migrations end at V${target}`;
const tables = () => query("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")?.[0];

if (restored === undefined) {
  fail("its Flyway schema version could not be read", `if the backend is running, run \`${manualRestart}\` so Flyway migrates it`);
}
if (restored === null && tables() !== "0") {
  // Flyway refuses a non-empty schema without a history (no baseline-on-migrate): a restart would crash-loop.
  console.warn("restore: WARNING — the dump has tables but no Flyway history, so it is not a Doučko dump. Nothing was restarted.");
} else if (target !== null && restored !== null && compareVersions(restored, target) > 0) {
  console.warn(
    `restore: WARNING — the dump's schema (V${restored}) is newer than expected (${expected}). ` +
      "A backend built from this checkout does not know it; use the version the dump was taken with. Nothing was restarted.",
  );
} else if (target !== null && (restored === null || compareVersions(restored, target) < 0)) {
  const running = backendRunning();
  const gap = `the dump's schema is at ${label(restored)} and ${expected}`;
  if (running === undefined) {
    fail(`${gap}, and docker compose could not say whether the backend runs`, `if it does, run \`${manualRestart}\``);
  } else if (!running) {
    console.log(`restore: ${gap}; the backend is not running, so Flyway migrates the database when it next starts (npm run stack:up).`);
  } else {
    console.log(`restore: ${gap}; the running backend migrates only at startup, so it is restarted now.`);
    const restart = spawnSync("docker", [...composeArgs(opts.project), "restart", "backend"], { stdio: ["ignore", "inherit", "inherit"] });
    if (restart.status !== 0 || !backendHealthy()) {
      fail("the backend did not come back healthy after the restart", `check \`${composeHint} logs backend\`, fix the cause and run \`${manualRestart}\``);
    }
    const migrated = schemaVersion();
    if (migrated === undefined) {
      fail("its schema version could not be read after the restart", `check \`${composeHint} logs backend\`, then run \`${manualRestart}\` if it is behind`);
    }
    if (migrated === null || compareVersions(migrated, target) < 0) {
      const rebuild = opts.project ? `${composeHint} up -d --build --wait backend` : "npm run stack:up";
      fail(`the backend restarted with the schema at ${label(migrated)}, not V${target}: its image is older than this checkout`, `rebuild it with \`${rebuild}\``);
    }
    console.log(`restore: backend restarted; Flyway migrated the restored schema to V${migrated}.`);
  }
}
