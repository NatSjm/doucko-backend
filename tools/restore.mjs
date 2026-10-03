// NFR-10: restore a dump written by `npm run backup` into the running database.
//
//   npm run restore -- --yes <file>
//   node tools/restore.mjs --yes [--project <compose project>] <file>
//
// Restoring replaces the current data (pg_restore --clean --if-exists), so it needs --yes.
import { spawnSync } from "node:child_process";
import { closeSync, existsSync, openSync } from "node:fs";
import { composeArgs, parseArgs } from "./lib/compose.mjs";

const { opts, positional } = parseArgs(process.argv.slice(2), ["project"]);
const file = positional[0];

if (!file || !existsSync(file)) {
  console.error("usage: node tools/restore.mjs --yes [--project <name>] <backup file>");
  process.exit(2);
}
if (!opts.yes) {
  console.error(`restore: this replaces the current database with ${file}. Re-run with --yes to proceed.`);
  process.exit(1);
}

const fd = openSync(file, "r");
const result = spawnSync(
  "docker",
  [...composeArgs(opts.project), "exec", "-T", "db", "pg_restore", "-U", "doucko", "-d", "doucko", "--clean", "--if-exists", "--no-owner", "--exit-on-error"],
  { stdio: [fd, "inherit", "inherit"] },
);
closeSync(fd);

if (result.status !== 0) {
  console.error(`restore: pg_restore failed (exit ${result.status ?? result.error?.message}).`);
  process.exit(1);
}
console.log(`restore complete: ${file}`);
