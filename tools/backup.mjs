// NFR-10: dump the Doučko database to a file OUTSIDE every repository.
//
//   npm run backup                          # → ~/doucko-backups/doucko-<UTC timestamp, ms>.dump
//   node tools/backup.mjs --out <dir> [--project <compose project>]
//
// pg_dump runs inside the db container (`docker compose exec`) in custom format (-Fc). The binary
// stream goes straight into a file descriptor, never through a console, so Windows code pages and
// newline translation cannot corrupt it. Restore with `npm run restore -- --yes <file>`.
import { spawnSync } from "node:child_process";
import { closeSync, existsSync, mkdirSync, openSync, rmSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { composeArgs, parseArgs } from "./lib/compose.mjs";

const { opts } = parseArgs(process.argv.slice(2), ["out", "project"]);
const outDir = resolve(opts.out ?? join(homedir(), "doucko-backups"));

// A backup holds the child's data (NFR-3); it must never land inside a git working tree.
for (let dir = outDir; ; dir = dirname(dir)) {
  if (existsSync(join(dir, ".git"))) {
    console.error(`backup: ${outDir} is inside the git working tree ${dir} — choose a directory outside every repository.`);
    process.exit(1);
  }
  if (dirname(dir) === dir) break;
}

// Owner-only directory and file (POSIX modes; on Windows the profile ACL applies). "wx" never
// overwrites an earlier dump; the millisecond stamp keeps two runs in one second apart.
mkdirSync(outDir, { recursive: true, mode: 0o700 });
const stamp = new Date().toISOString().replace(/[-:]/g, "").replace("T", "-").replace(".", "-").replace("Z", "");
const file = join(outDir, `doucko-${stamp}.dump`);
const fd = openSync(file, "wx", 0o600);
const result = spawnSync("docker", [...composeArgs(opts.project), "exec", "-T", "db", "pg_dump", "-U", "doucko", "-d", "doucko", "-Fc"], {
  stdio: ["ignore", fd, "inherit"],
});
closeSync(fd);

if (result.status !== 0 || statSync(file).size === 0) {
  rmSync(file, { force: true });
  console.error(`backup: pg_dump failed (exit ${result.status ?? result.error?.message}). Is the stack running? (npm run stack:up)`);
  process.exit(1);
}
console.log(`backup written: ${file} (${statSync(file).size} bytes)`);
