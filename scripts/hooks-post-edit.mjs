// Claude Code PostToolUse hook (Edit|Write) — the agent's senses are the
// linter, not vibes. Doučko multi-repo adaptation of upstream
// templates/hooks/claude-code-hooks.json (which inlined an ESLint one-liner):
// rules are per repo in factory.config.json `postEdit`, e.g.
//   frontend  *.ts/*.tsx -> npx eslint --no-warn-ignored {file}
//   backend   *.kt/*.kts -> Spotless (ktlint) check on that one file
// Installed as scripts/hooks-post-edit.mjs. Exit 2 feeds the tool output back
// to the agent so it fixes the violation in the same loop.
//
// A rule whose tool is not installed yet (`requires` missing — e.g. before the
// Spring/Vite scaffold exists) prints a one-line notice and exits 0: this hook
// is feedback, the git hooks + CI are the gates.
import { execSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { relative } from "node:path";
import { isExcluded, loadConfig } from "./lib/factory-config.mjs";

let input = {};
try {
  input = JSON.parse(readFileSync(0, "utf8"));
} catch {
  process.exit(0);
}
const abs = input?.tool_input?.file_path;
if (!abs) process.exit(0);
const root = process.cwd();
const rel = relative(root, abs).replaceAll("\\", "/");
if (rel.startsWith("..") || /(^|\/)(node_modules|target|dist)\//.test(rel)) process.exit(0);

const cfg = loadConfig(root);
if (isExcluded(cfg, rel)) process.exit(0);
for (const rule of cfg.postEdit ?? []) {
  if (!new RegExp(rule.match).test(rel)) continue;
  if (rule.requires && !existsSync(rule.requires)) {
    console.error(`post-edit [${rule.name}]: skipped — ${rule.requires} not installed yet (${rule.hint ?? "stack not scaffolded"})`);
    continue;
  }
  // {file} = repo-relative path; {fileRegex} = an anchored regex for tools
  // that filter by pattern (Spotless -DspotlessFiles).
  const fileRegex = `.*${rel.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`;
  const cmd = rule.run.replaceAll("{file}", `"${rel}"`).replaceAll("{fileRegex}", fileRegex);
  try {
    execSync(cmd, { stdio: "pipe", timeout: (rule.timeoutSec ?? 60) * 1000 });
  } catch (e) {
    console.error(`post-edit [${rule.name}] ${rel}:\n${e.stdout ?? ""}${e.stderr ?? ""}`.trim());
    process.exit(2);
  }
}
process.exit(0);
