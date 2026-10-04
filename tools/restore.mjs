// NFR-10: restore a dump written by `npm run backup` into the running database.
//
//   npm run restore -- --yes <file>
//   node tools/restore.mjs --yes [--project <compose project>] [--backup-dir <dir>] <file>
//
// Restoring replaces the current data (pg_restore --clean --if-exists), so it needs --yes. Before
// anything is dropped it (1) checks that the file is a readable pg_dump archive and (2) takes a
// safety backup of the current data (default ~/doucko-backups). The restore then runs in a single
// transaction: a truncated or failing dump rolls back and leaves the live data as it was.
import { spawnSync } from "node:child_process";
import { closeSync, existsSync, openSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { composeArgs, parseArgs } from "./lib/compose.mjs";

const { opts, positional } = parseArgs(process.argv.slice(2), ["project", "backup-dir"]);
const file = positional[0];

if (!file || !existsSync(file)) {
  console.error("usage: node tools/restore.mjs --yes [--project <name>] [--backup-dir <dir>] <backup file>");
  process.exit(2);
}
if (!opts.yes) {
  console.error(`restore: this replaces the current database with ${file}. Re-run with --yes to proceed.`);
  process.exit(1);
}

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

const result = inDb("pg_restore", "-U", "doucko", "-d", "doucko", "--clean", "--if-exists", "--no-owner", "--single-transaction", "--exit-on-error");
if (result.status !== 0) {
  console.error(`restore: pg_restore failed (exit ${result.status ?? result.error?.message}); the transaction was rolled back and the data is unchanged.`);
  process.exit(1);
}
console.log(`restore complete: ${file}`);
