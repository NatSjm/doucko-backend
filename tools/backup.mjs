// NFR-10: dump the Doučko database to a file OUTSIDE every repository.
//
//   npm run backup                          # → ~/doucko-backups/doucko-<UTC timestamp, ms>.dump
//   node tools/backup.mjs --out <dir> [--project <compose project>]
//
// pg_dump runs inside the db container (`docker compose exec`) in custom format (-Fc). The binary
// stream goes straight into a file descriptor, never through a console, so Windows code pages and
// newline translation cannot corrupt it. Restore with `npm run restore -- --yes <file>`.
import { spawnSync } from "node:child_process";
import { chmodSync, closeSync, existsSync, mkdirSync, openSync, realpathSync, rmSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { composeArgs, parseArgs } from "./lib/compose.mjs";

const { opts } = parseArgs(process.argv.slice(2), ["out", "project"]);
const outDir = resolve(opts.out ?? join(homedir(), "doucko-backups"));

const windows = process.platform === "win32";
const home = realpathSync(homedir());
const within = (path, root) => {
  const rel = relative(root, path);
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
};

/**
 * A backup holds the child's data (NFR-3): never inside a git working tree, and owner-only. POSIX
 * gets 0700/0600 modes; Windows ignores them, so there the dump must stay under the user profile,
 * whose ACL is owner-only. Checked on the path as given and again on its real path after mkdir,
 * so a symlink or junction cannot lead around it.
 */
function problem(dir) {
  for (let d = dir; ; d = dirname(d)) {
    if (existsSync(join(d, ".git"))) return `${outDir} is inside the git working tree ${d} — choose a directory outside every repository.`;
    if (dirname(d) === d) break;
  }
  if (windows && !within(dir, home)) return `${outDir} is outside your user profile (${home}); on Windows only the profile keeps the dump owner-only.`;
  return null;
}
function refuse(message) {
  console.error(`backup: ${message}`);
  process.exit(1);
}

const lexical = problem(outDir);
if (lexical) refuse(lexical);
const existed = existsSync(outDir);
mkdirSync(outDir, { recursive: true, mode: 0o700 });
const real = problem(realpathSync(outDir));
if (real) {
  if (!existed) rmSync(outDir, { recursive: true, force: true });
  refuse(real);
}
if (!windows) chmodSync(outDir, 0o700); // also when the directory already existed

// "wx" never overwrites an earlier dump; the millisecond stamp keeps two runs in one second apart.
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
