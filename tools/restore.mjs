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
import { spawnSync } from "node:child_process";
import { closeSync, openSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { composeArgs, parseArgs } from "./lib/compose.mjs";

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
