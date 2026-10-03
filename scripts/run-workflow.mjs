// Doučko factory — workflow runner. Executes the harness workflows
// (.claude/workflows/*.js: spec-pipeline, review-gate, eval-suite, ...)
// outside a native workflow engine, by giving them the globals they expect
// and running every agent() step as a headless `claude -p` session.
//
// Workflow API provided (the full surface used by factory/workflows/*.js):
//   agent(prompt, { label, phase, agentType?, schema? })
//       -> parsed object when `schema` is given, else the final text;
//          null when the step failed (workflows already tolerate null).
//       agentType -> `claude --agent <type>` (definition in .claude/agents/,
//       which brings its own tools); untyped steps get --untyped-tools
//       (default read-only: Read,Grep,Glob). schema -> `--json-schema`.
//   parallel([() => promise, ...])      -> Promise.all over the thunks
//   pipeline(items, stage1, stage2, ..) -> each item flows through the stages
//       concurrently; stage n>1 receives (previousResult, item, index)
//   phase(title), log(message), args (from --args / --args-file)
//
// Every run writes a record (each step: label, phase, agent, duration, cost,
// session, outcome, result) to trace/workflow-runs/<stamp>-<name>.json and a
// fail-open "workflow-run" event to trace/ledger.jsonl. Maker != checker is
// preserved: every agent() step is a fresh headless session.
//
// Usage:
//   node scripts/run-workflow.mjs <name|path.js> [--args '<json>' | --args-file <file>]
//        [--concurrency 4] [--model <model>] [--budget-usd 2] [--max-total-usd 20]
//        [--timeout-min 20] [--untyped-tools Read,Grep,Glob]
//        [--permission-mode acceptEdits] [--dry-run]
//   --dry-run  no API calls: prints each step's command and returns a minimal
//              schema-valid stub, so a workflow's wiring can be checked for free.
//
// Exit: 0 = workflow returned and every step succeeded; 1 = a step failed, the
// workflow threw, or it returned { error }; 2 = usage / setup error.
// Each agent() step is a billed Claude run — mind --max-total-usd.
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, isAbsolute, join, resolve } from "node:path";

const root = process.cwd();
const argv = process.argv.slice(2);

function opt(name, fallback) {
  const i = argv.indexOf(`--${name}`);
  if (i === -1) return fallback;
  const v = argv[i + 1];
  if (v === undefined || v.startsWith("--")) usage(`--${name} needs a value`);
  return v;
}
function usage(msg) {
  if (msg) console.error(`run-workflow: ${msg}`);
  console.error(
    "usage: node scripts/run-workflow.mjs <name|path.js> [--args '<json>' | --args-file <file>] [--concurrency N] [--model M] [--budget-usd X] [--max-total-usd Y] [--timeout-min T] [--untyped-tools Read,Grep,Glob] [--permission-mode acceptEdits] [--dry-run]",
  );
  process.exit(2);
}

const VALUE_FLAGS = new Set(["args", "args-file", "concurrency", "model", "budget-usd", "max-total-usd", "timeout-min", "untyped-tools", "permission-mode"]);
const positional = argv.filter((a, i) => !a.startsWith("--") && !(i > 0 && VALUE_FLAGS.has(argv[i - 1].replace(/^--/, ""))));
const target = positional[0];
if (!target) usage("missing workflow name");

const DRY = argv.includes("--dry-run");
const CONCURRENCY = Math.max(1, Number(opt("concurrency", "4")) || 4);
const MODEL = opt("model", null);
const BUDGET_PER_AGENT = opt("budget-usd", "2");
const MAX_TOTAL = Number(opt("max-total-usd", "20"));
const TIMEOUT_MS = Math.max(1, Number(opt("timeout-min", "20"))) * 60_000;
const UNTYPED_TOOLS = opt("untyped-tools", "Read,Grep,Glob");
const PERMISSION_MODE = opt("permission-mode", "acceptEdits");
const CLAUDE_BIN = process.env.CLAUDE_BIN || "claude";

// ---------- resolve workflow + args ----------
const wfPath = target.endsWith(".js")
  ? (isAbsolute(target) ? target : resolve(root, target))
  : join(root, ".claude", "workflows", `${target}.js`);
if (!existsSync(wfPath)) usage(`workflow not found: ${wfPath}`);
const wfName = basename(wfPath, ".js");

let args = {};
try {
  const file = opt("args-file", null);
  const raw = file ? readFileSync(resolve(root, file), "utf8") : opt("args", null);
  if (raw) args = JSON.parse(raw);
} catch (e) {
  usage(`--args / --args-file is not valid JSON: ${e.message}`);
}

if (!DRY) {
  const probe = spawnSync(CLAUDE_BIN, ["--version"], { encoding: "utf8", windowsHide: true });
  if (probe.error || probe.status !== 0) usage(`cannot run "${CLAUDE_BIN} --version" — install Claude Code or set CLAUDE_BIN`);
}

// ---------- run record ----------
const startedAt = new Date();
const steps = [];
const logs = [];
let currentPhase = null;
let totalCost = 0;
let fatal = null; // auth / budget: stop starting new steps

const stamp = startedAt.toISOString().replace(/[-:]/g, "").replace(/\..*$/, "").replace("T", "-");
const recordDir = join(root, "trace", "workflow-runs");
const recordPath = join(recordDir, `${stamp}-${wfName}${DRY ? "-dry" : ""}.json`);

// ---------- concurrency gate ----------
let active = 0;
const waiting = [];
async function withSlot(fn) {
  if (active >= CONCURRENCY) await new Promise((r) => waiting.push(r));
  active += 1;
  try {
    return await fn();
  } finally {
    active -= 1;
    waiting.shift()?.();
  }
}

// ---------- schema stub (dry run) ----------
function stub(schema) {
  if (!schema || typeof schema !== "object") return "dry-run";
  if (schema.enum) return schema.enum[0];
  switch (schema.type) {
    case "object": {
      const o = {};
      for (const k of schema.required ?? []) o[k] = stub(schema.properties?.[k]);
      return o;
    }
    case "array": return [];
    case "boolean": return true;
    case "integer": case "number": return 0;
    default: return "dry-run";
  }
}

// ---------- one headless claude step ----------
function runClaude(cmdArgs, prompt) {
  return new Promise((resolveRun) => {
    const child = spawn(CLAUDE_BIN, cmdArgs, { cwd: root, windowsHide: true, stdio: ["pipe", "pipe", "pipe"] });
    let out = "";
    let err = "";
    const timer = setTimeout(() => {
      err += `\n[run-workflow] timed out after ${TIMEOUT_MS / 60_000} min`;
      child.kill();
    }, TIMEOUT_MS);
    child.stdout.on("data", (d) => (out += d));
    child.stderr.on("data", (d) => (err += d));
    child.on("error", (e) => {
      clearTimeout(timer);
      resolveRun({ code: -1, out, err: `${err}\n${e.message}` });
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      resolveRun({ code, out, err });
    });
    child.stdin.end(prompt);
  });
}

function parseStructured(j) {
  if (j.structured_output !== undefined && j.structured_output !== null) return j.structured_output;
  const text = String(j.result ?? "").trim();
  const fenced = [...text.matchAll(/```(?:json)?\s*([\s\S]*?)```/g)].pop()?.[1];
  for (const candidate of [fenced, text]) {
    if (!candidate) continue;
    try {
      return JSON.parse(candidate);
    } catch {
      /* try next */
    }
  }
  return undefined;
}

async function agent(prompt, opts = {}) {
  const label = opts.label ?? `step-${steps.length + 1}`;
  const step = { label, phase: opts.phase ?? currentPhase, agentType: opts.agentType ?? null, structured: !!opts.schema, ok: false };
  steps.push(step);
  return withSlot(async () => {
    if (fatal) {
      step.error = `skipped: ${fatal}`;
      console.log(`  - ${label}: skipped (${fatal})`);
      return null;
    }
    if (opts.agentType && !existsSync(join(root, ".claude", "agents", `${opts.agentType}.md`))) {
      step.error = `agent definition .claude/agents/${opts.agentType}.md not found`;
      console.log(`  ✗ ${label}: ${step.error}`);
      return null;
    }
    const cmd = ["-p", "--output-format", "json", "--no-session-persistence", "--permission-mode", PERMISSION_MODE, "--max-budget-usd", String(BUDGET_PER_AGENT)];
    if (opts.agentType) cmd.push("--agent", opts.agentType);
    else cmd.push("--tools", UNTYPED_TOOLS);
    if (opts.schema) cmd.push("--json-schema", JSON.stringify(opts.schema));
    if (MODEL) cmd.push("--model", MODEL);
    step.command = `${CLAUDE_BIN} ${cmd.map((c) => (/[\s"{]/.test(c) ? "<…>" : c)).join(" ")}`;
    step.promptChars = String(prompt).length;
    const t0 = Date.now();

    if (DRY) {
      step.ok = true;
      step.durationMs = 0;
      step.result = opts.schema ? stub(opts.schema) : `[dry-run] ${label}`;
      console.log(`  · ${label} [${opts.agentType ?? `tools:${UNTYPED_TOOLS}`}${opts.schema ? ", schema" : ""}] ${step.promptChars} chars`);
      return step.result;
    }

    console.log(`  → ${label} [${opts.agentType ?? `tools:${UNTYPED_TOOLS}`}${opts.schema ? ", schema" : ""}]`);
    const r = await runClaude(cmd, String(prompt));
    step.durationMs = Date.now() - t0;
    let j = null;
    try {
      j = JSON.parse(r.out);
    } catch {
      step.error = `no JSON result (exit ${r.code}): ${(r.err || r.out).trim().slice(0, 500)}`;
    }
    if (j) {
      step.sessionId = j.session_id ?? null;
      step.costUsd = j.total_cost_usd ?? 0;
      step.numTurns = j.num_turns ?? null;
      totalCost += step.costUsd;
      if (j.is_error) {
        step.error = String(j.result ?? j.subtype ?? "error").slice(0, 500);
        if (/authenticate|oauth|log ?in|api key/i.test(step.error)) fatal = `claude CLI not authenticated (${step.error})`;
      } else if (opts.schema) {
        const parsed = parseStructured(j);
        if (parsed === undefined) step.error = "result did not contain JSON for the requested schema";
        else {
          step.ok = true;
          step.result = parsed;
        }
      } else {
        step.ok = true;
        step.result = j.result ?? "";
      }
    }
    if (!fatal && totalCost >= MAX_TOTAL) fatal = `--max-total-usd ${MAX_TOTAL} reached ($${totalCost.toFixed(2)})`;
    console.log(`  ${step.ok ? "✓" : "✗"} ${label} ${(step.durationMs / 1000).toFixed(0)}s $${(step.costUsd ?? 0).toFixed(3)}${step.ok ? "" : ` — ${step.error}`}`);
    return step.ok ? step.result : null;
  });
}

const parallel = (thunks) => Promise.all(thunks.map((t) => (typeof t === "function" ? t() : t)));
const pipeline = (items, ...stages) =>
  Promise.all(
    items.map(async (item, i) => {
      let res = await stages[0](item, i);
      for (const stage of stages.slice(1)) res = await stage(res, item, i);
      return res;
    }),
  );
function phase(title) {
  currentPhase = title;
  console.log(`\n== ${title}`);
}
function log(message) {
  logs.push({ phase: currentPhase, message: String(message) });
  console.log(`  [log] ${message}`);
}

// ---------- load + execute ----------
// Workflows are ES-module-shaped scripts with top-level await and a
// top-level `return`; `export const meta` is captured, the body runs inside
// an async function that receives the workflow globals.
const src = readFileSync(wfPath, "utf8");
if (/^\s*import\s/m.test(src)) usage(`${wfName}: workflows must not import modules (none of the harness workflows do)`);
const body = src.replace(/^export\s+const\s+meta\s*=/m, "__ctx.meta =");
const AsyncFunction = Object.getPrototypeOf(async () => {}).constructor;
const __ctx = { meta: null };
let returned;
let thrown = null;
console.log(`workflow ${wfName}${DRY ? " (dry run — no API calls)" : ""} — concurrency ${CONCURRENCY}, max total $${MAX_TOTAL}`);
try {
  const fn = new AsyncFunction("__ctx", "agent", "parallel", "pipeline", "phase", "log", "args", body);
  returned = await fn(__ctx, agent, parallel, pipeline, phase, log, args);
} catch (e) {
  thrown = e;
  console.error(`\nworkflow threw: ${e?.stack ?? e}`);
}

// ---------- record ----------
const failed = steps.filter((s) => !s.ok);
const returnedError = returned && typeof returned === "object" && returned.error ? String(returned.error) : null;
const ok = !thrown && !returnedError && failed.length === 0;
const record = {
  workflow: wfName,
  file: wfPath.replace(root, ".").replaceAll("\\", "/"),
  description: __ctx.meta?.description ?? null,
  dryRun: DRY,
  startedAt: startedAt.toISOString(),
  finishedAt: new Date().toISOString(),
  args,
  settings: { concurrency: CONCURRENCY, model: MODEL, budgetPerAgentUsd: Number(BUDGET_PER_AGENT), maxTotalUsd: MAX_TOTAL, untypedTools: UNTYPED_TOOLS, permissionMode: PERMISSION_MODE },
  outcome: ok ? "ok" : "failed",
  stoppedBecause: fatal,
  error: thrown ? String(thrown?.message ?? thrown) : returnedError,
  totalCostUsd: Number(totalCost.toFixed(4)),
  steps,
  logs,
  returned: returned ?? null,
};
mkdirSync(recordDir, { recursive: true });
writeFileSync(recordPath, `${JSON.stringify(record, null, 2)}\n`);

try {
  if (existsSync(join(root, "scripts", "ledger.mjs"))) {
    spawnSync(process.execPath, [join(root, "scripts", "ledger.mjs"), "emit", JSON.stringify({
      event: "workflow-run", check: wfName, exitCode: ok ? 0 : 1,
      meta: { dryRun: DRY, steps: steps.length, failed: failed.length, costUsd: record.totalCostUsd, record: recordPath.replace(root, ".").replaceAll("\\", "/") },
    })], { stdio: "ignore", cwd: root });
  }
} catch {
  /* fail-open: telemetry never blocks */
}

console.log(`\nreturned: ${JSON.stringify(returned ?? null, null, 2)?.slice(0, 4000)}`);
console.log(`\nworkflow ${wfName}: ${ok ? "ok" : "FAILED"} — ${steps.length} step(s), ${failed.length} failed, $${totalCost.toFixed(2)}${fatal ? ` — stopped: ${fatal}` : ""}`);
console.log(`record: ${recordPath.replace(root, ".").replaceAll("\\", "/")}`);
process.exit(ok ? 0 : 1);
