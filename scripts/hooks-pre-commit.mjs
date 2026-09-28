// Git pre-commit hook (deterministic inner loop). Doučko multi-repo adaptation
// of upstream templates/hooks/pre-commit.mjs. Installed as
// scripts/hooks-pre-commit.mjs; .githooks/pre-commit calls it.
// Fast, staged-scope checks only; the full battery runs at gates and in CI.
//
//   1. secret hygiene — env files, key/token patterns (upstream)
//   2. content guard  — NFR-3 (child PII) / NFR-4 (copyrighted workbook
//                       content): factory.config.json `forbidden` rules
//   3. stack checks   — factory.config.json `precommit` (ktlint via Maven
//                       Spotless in the backend; ESLint + tsc in the frontend;
//                       OpenSpec validation in the docs repo)
//   4. traceability + trajectory — regenerate reports and stage them
import { execSync, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { loadConfig } from "./lib/factory-config.mjs";

// Fail-open process-health telemetry: never blocks a commit.
process.on("exit", (code) => {
  try {
    if (!existsSync("scripts/ledger.mjs")) return;
    spawnSync(
      process.execPath,
      ["scripts/ledger.mjs", "emit", JSON.stringify({ event: "hook-run", check: "pre-commit", exitCode: code ?? 0 })],
      { stdio: "ignore" },
    );
  } catch {
    /* fail-open */
  }
});

const cfg = loadConfig(process.cwd());
if (cfg.configError) {
  console.error(`pre-commit: ${cfg.configError}`);
  process.exit(1);
}
const run = (cmd) => execSync(cmd, { stdio: "inherit" });
const capture = (cmd) => execSync(cmd, { encoding: "utf8" }).trim();
const quote = (f) => `"${f}"`;

const staged = capture("git diff --cached --name-only --diff-filter=ACM").split("\n").filter(Boolean);

// 1 — secret hygiene
const envViolations = staged.filter((f) => /(^|\/)\.env(\.|$)/.test(f) && !f.endsWith(".example"));
if (envViolations.length) {
  console.error(`pre-commit: refusing to commit env file(s): ${envViolations.join(", ")}`);
  process.exit(1);
}
const SECRET_PATTERNS = [
  /-----BEGIN (RSA |EC )?PRIVATE KEY-----/,
  /\b(sk|rk|re|ghp|gho|xox[bap])_[A-Za-z0-9]{16,}\b/,
  /\bsk-(proj-)?[A-Za-z0-9_-]{20,}\b/, // OpenAI keys (Future AI phase, NFR-14)
  /\bAKIA[0-9A-Z]{16}\b/,
  /(postgres(ql)?|jdbc:postgresql):\/\/[^\s'"]+:[^\s'"]+@/,
];
const BINARY = /\.(png|webm|jpg|jpeg|gif|pdf|ico|woff2?|jar|class)$/i;
for (const file of staged) {
  if (BINARY.test(file)) continue;
  let content = "";
  try {
    content = capture(`git show :${quote(file)}`);
  } catch {
    continue;
  }
  for (const pattern of SECRET_PATTERNS) {
    if (pattern.test(content)) {
      console.error(`pre-commit: possible secret in ${file} (pattern ${pattern}). Use env vars; bypass only with an explicit allowlist edit to this hook.`);
      process.exit(1);
    }
  }
}

// 2 — content guard (NFR-3 privacy, NFR-4 copyright)
for (const rule of cfg.forbidden ?? []) {
  const re = new RegExp(rule.pattern, "i");
  const allow = rule.allow ? new RegExp(rule.allow, "i") : null;
  const hits = staged.filter((f) => re.test(f) && !(allow && allow.test(f)));
  if (hits.length) {
    console.error(`pre-commit: ${rule.reason}\n  refused: ${hits.join(", ")}${rule.allow ? `\n  allowed only under: /${rule.allow}/` : ""}`);
    process.exit(1);
  }
}

// 3 — stack checks. A rule fires when a staged file matches `when`; if its
// tool is not installed yet (`requires` path missing) the commit FAILS with
// the install hint — a missing linter is never a silent pass.
for (const rule of cfg.precommit ?? []) {
  const re = new RegExp(rule.when);
  const files = staged.filter((f) => re.test(f) && !(cfg.productCode.exclude ?? []).some((ex) => f.startsWith(ex)));
  if (!files.length) continue;
  if (rule.requires && !existsSync(rule.requires)) {
    console.error(`pre-commit [${rule.name}]: staged ${files.length} matching file(s) but ${rule.requires} is missing — ${rule.hint ?? "install the stack tooling first"}`);
    process.exit(1);
  }
  const cmd = rule.run.replace("{files}", files.map(quote).join(" "));
  console.log(`pre-commit [${rule.name}]: ${cmd.length > 160 ? `${cmd.slice(0, 157)}...` : cmd}`);
  run(cmd);
}

// 4 — traceability + trajectory. They regenerate reports; stage them so the
// commit always carries fresh versions (CI --check-fresh stays green).
run("node scripts/check-traceability.mjs");
run("git add docs/qa/traceability-report.md trace/trace.json");
run("node scripts/check-trajectory.mjs");
run("git add docs/qa/trajectory-report.md trace/trajectory.json");

console.log("pre-commit: all deterministic checks passed");
