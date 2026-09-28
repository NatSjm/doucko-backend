// Doučko factory — shared configuration for every gate script (multi-repo adaptation).
//
// Upstream Project Factory assumes ONE Node repo: docs/requirements.md,
// openspec/, and app/lib/src code all live together. Doučko splits delivery
// across three repositories:
//
//   docs      OpenSpec STORE "doucko" — product requirements, capability plan,
//             product-level specs, ADRs, openapi.yaml, and this harness' source
//   backend   Kotlin + Spring Boot + Maven — local openspec/ root that
//             `references:` the store; implementation-level changes only
//   frontend  React + Vite + TS — same shape as backend
//
// Each repo carries a `factory.config.json` at its root. This module reads it
// and answers the questions the upstream scripts used to hardcode:
//   - where do requirements / the capability plan / product specs live?
//     (code repos read them through the pinned `contract/` submodule, or the
//     sibling ../docs checkout as an explicitly-WARNED unpinned fallback)
//   - what counts as product code / a test file in THIS repo's stack?
//   - which FRs does THIS repo own (the plan's Repo(s) column), and which
//     verification methods is it responsible for?
//
// Stdlib only; never throws on a missing/partial config (defaults below keep
// the upstream single-repo behavior so a mis-installed repo still gates).
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

export const CONFIG_FILE = "factory.config.json";

const DEFAULTS = {
  project: "project",
  repo: "app",
  store: null,
  contract: null,
  paths: {
    requirements: "docs/requirements.md",
    plan: "docs/mvp-capability-plan.md",
    storeSpecs: null,
    specsDir: "openspec/specs",
    changesDir: "openspec/changes",
  },
  productCode: {
    dirs: ["app", "src", "lib", "server", "pages", "components", "api"],
    ext: "\\.(m?[jt]sx?|cjs|kts?|py|go|rb|rs|java|cs|php|vue|svelte)$",
    exclude: [],
  },
  tests: {
    dirs: ["lib", "tests", "test", "app", "src", "components", "evals", "e2e"],
    pattern: "\\.(test|spec|eval)\\.(ts|tsx|js|mjs)$|(Test|Tests|IT|Spec)\\.kt$",
  },
  featureCodePattern: "^(app|lib|db|src)/",
  moduleScope: { pattern: "^lib/([^/]+)/", shared: ["auth", "db", "shared", "ui", "utils", "common", "email"] },
  acceptanceMethods: null, // null = every method (upstream single-repo behavior)
  battery: null,
  precommit: [],
  postEdit: [],
  coverage: { format: "istanbul-summary", summary: "coverage/coverage-summary.json" },
  workspace: null,
};

function deepMerge(base, over) {
  if (Array.isArray(over) || over === null || typeof over !== "object") return over === undefined ? base : over;
  const out = { ...(base ?? {}) };
  for (const [k, v] of Object.entries(over)) out[k] = deepMerge(base?.[k], v);
  return out;
}

const cache = new Map();

/** Load the repo's factory config (merged over defaults) with resolved paths. */
export function loadConfig(root = process.cwd()) {
  if (cache.has(root)) return cache.get(root);
  let raw = {};
  let configError = null;
  const file = join(root, CONFIG_FILE);
  if (existsSync(file)) {
    try {
      raw = JSON.parse(readFileSync(file, "utf8"));
    } catch (e) {
      configError = `${CONFIG_FILE} is not valid JSON (${e.message})`;
    }
  }
  const cfg = deepMerge(DEFAULTS, raw);
  cfg.present = existsSync(file);
  cfg.configError = configError;
  const contract = resolveContract(root, cfg.contract);
  cfg.contractRoot = contract.root; // relative path, "." for the docs repo
  cfg.contractSource = contract.source; // self | submodule | sibling-unpinned | missing
  cfg.contractNote = contract.note;
  const sub = (p) => (typeof p === "string" ? p.replaceAll("{contract}", cfg.contractRoot ?? "<contract-missing>").replace(/^\.\//, "") : p);
  cfg.paths = Object.fromEntries(Object.entries(cfg.paths).map(([k, v]) => [k, sub(v)]));
  cfg.productCodeExt = new RegExp(cfg.productCode.ext, "i");
  cfg.testPattern = new RegExp(cfg.tests.pattern);
  cfg.featureCodeRe = new RegExp(cfg.featureCodePattern);
  cfg.moduleScopeRe = new RegExp(cfg.moduleScope.pattern);
  cache.set(root, cfg);
  return cfg;
}

// Where do product docs come from? The docs repo IS the contract. Code repos
// prefer the pinned git submodule (reproducible, identical in CI); the sibling
// checkout used by the workset is accepted only with a visible warning.
function resolveContract(root, contract) {
  if (!contract) return { root: ".", source: "self", note: null };
  const marker = contract.marker ?? "docs/requirements.md";
  const sub = contract.submodule;
  if (sub && existsSync(join(root, sub)) && readdirSafe(join(root, sub)).length > 0) {
    return { root: sub, source: "submodule", note: null };
  }
  const sib = contract.sibling;
  if (sib && existsSync(join(root, sib, ".openspec-store"))) {
    return {
      root: sib,
      source: "sibling-unpinned",
      note:
        `contract read from the sibling checkout ${sib} (UNPINNED) — ${sub}/ submodule is absent or empty. ` +
        `Fine for local work before remotes exist; CI requires the pinned submodule ` +
        `(git submodule add <docs-remote-url> ${sub}). Marker: ${marker}`,
    };
  }
  return {
    root: sub ?? sib ?? ".",
    source: "missing",
    note: `contract not found: neither ${sub}/ (submodule) nor ${sib} (sibling store checkout) is available — run \`git submodule update --init\``,
  };
}

function readdirSafe(p) {
  try {
    return readdirSync(p);
  } catch {
    return [];
  }
}

const norm = (p) => p.replaceAll("\\", "/");

/** Is `rel` (repo-relative, forward or back slashes) excluded vendored code? */
export function isExcluded(cfg, rel) {
  const r = norm(rel);
  return (cfg.productCode.exclude ?? []).some((ex) => r === ex || r.startsWith(ex.endsWith("/") ? ex : `${ex}/`));
}

export function isTestFile(cfg, rel) {
  return cfg.testPattern.test(norm(rel)) || /(^|\/)(integration|e2e)(\/|\.)/.test(norm(rel));
}

/** Product (non-test, non-vendored) source file under this repo's product dirs? */
export function isProductFile(cfg, rel) {
  const r = norm(rel);
  if (!cfg.productCodeExt.test(r) || isExcluded(cfg, r) || isTestFile(cfg, r)) return false;
  return cfg.productCode.dirs.some((d) => r.startsWith(`${d.replace(/\/$/, "")}/`));
}

function* walk(root, rel) {
  const abs = join(root, rel);
  if (!existsSync(abs)) return;
  for (const e of readdirSafe(abs)) {
    if (e === "node_modules" || e === "target" || e === "dist" || e.startsWith(".")) continue;
    const r = rel ? `${rel}/${e}` : e;
    let st;
    try {
      st = statSync(join(root, r));
    } catch {
      continue;
    }
    if (st.isDirectory()) yield* walk(root, r);
    else yield r;
  }
}

/** Does this repo contain product code (the post-phase strictness trigger)? */
export function hasProductCode(root = process.cwd()) {
  const cfg = loadConfig(root);
  for (const d of cfg.productCode.dirs) {
    for (const f of walk(root, d.replace(/\/$/, ""))) if (isProductFile(cfg, f)) return true;
  }
  return false;
}

/** Yield repo-relative test files (per this repo's stack conventions). */
export function* testFiles(root = process.cwd()) {
  const cfg = loadConfig(root);
  const seen = new Set();
  for (const d of cfg.tests.dirs) {
    for (const f of walk(root, d.replace(/\/$/, ""))) {
      if (seen.has(f) || isExcluded(cfg, f) || !isTestFile(cfg, f)) continue;
      seen.add(f);
      yield f;
    }
  }
}

// ---------------------------------------------------------------------------
// Ownership: which repo(s) deliver each requirement. Parsed from the capability
// plan's coverage table — any markdown table whose header has a "Repo" or
// "Repos" column; rows keyed by an FR/NFR id. Example:
//   | Req | Slice | Repo(s) |
//   |---|---|---|
//   | FR-14 | add-answer-checker | backend |
//   | FR-27 | add-practice-flow | backend, frontend |
// ---------------------------------------------------------------------------
const ID_RE = /^\|\s*((?:FR|NFR|TC|BC)-(?:[A-Z0-9]+-)?\d+)\s*\|/;
const REPO_NAMES = ["docs", "backend", "frontend"];

export function parseOwnership(planText) {
  const owners = new Map(); // id -> Set<repo>
  let repoCol = -1;
  for (const line of (planText ?? "").split("\n")) {
    if (!line.trim().startsWith("|")) {
      repoCol = -1;
      continue;
    }
    const cells = line.split("|").slice(1, -1).map((c) => c.trim());
    const hdr = cells.findIndex((c) => /^repo(\(s\)|s)?$/i.test(c.replace(/\*/g, "")));
    if (hdr >= 0) {
      repoCol = hdr;
      continue;
    }
    if (repoCol < 0) continue;
    const m = line.match(ID_RE);
    if (!m) continue;
    const repos = (cells[repoCol] ?? "").toLowerCase().split(/[^a-z]+/).filter((r) => REPO_NAMES.includes(r));
    if (!owners.has(m[1])) owners.set(m[1], new Set());
    for (const r of repos) owners.get(m[1]).add(r);
  }
  return owners;
}

/**
 * Scope helper for per-repo checks. Returns:
 *   { planPresent, owners, owns(id) }
 * The docs repo "owns" every id (it holds the product-level chain). A code
 * repo owns exactly the ids the plan assigns to it; before the plan exists
 * (pre-G3) it owns nothing — callers render that as a visible warning, never
 * as coverage.
 */
export function ownership(root = process.cwd()) {
  const cfg = loadConfig(root);
  const planPath = join(root, cfg.paths.plan);
  const planPresent = existsSync(planPath);
  const owners = planPresent ? parseOwnership(readFileSync(planPath, "utf8")) : new Map();
  const owns = (id) => (cfg.repo === "docs" || !cfg.contract ? true : owners.get(id)?.has(cfg.repo) ?? false);
  return { planPresent, owners, owns, repo: cfg.repo };
}

/** Is `method` one this repo is responsible for proving? */
export function ownsMethod(cfg, method) {
  return !Array.isArray(cfg.acceptanceMethods) || cfg.acceptanceMethods.includes(method);
}

// ---------------------------------------------------------------------------
// Coverage summaries: istanbul json-summary (Vitest) or JaCoCo XML (Maven),
// normalized to { lines, statements, functions, branches } percentages.
// ---------------------------------------------------------------------------
export function readCoverage(root = process.cwd()) {
  const cfg = loadConfig(root);
  const file = join(root, cfg.coverage.summary);
  if (!existsSync(file)) return { file: cfg.coverage.summary, metrics: null };
  const text = readFileSync(file, "utf8");
  if (cfg.coverage.format === "jacoco-xml") {
    // Report-level counters are the LAST <counter> run directly under <report>.
    const body = text.replace(/<package[\s\S]*<\/package>/g, "");
    const pct = (type) => {
      const m = body.match(new RegExp(`<counter type="${type}" missed="(\\d+)" covered="(\\d+)"\\s*/>`));
      if (!m) return 0;
      const [missed, covered] = [Number(m[1]), Number(m[2])];
      return missed + covered === 0 ? 100 : Math.round((covered / (missed + covered)) * 10000) / 100;
    };
    return {
      file: cfg.coverage.summary,
      metrics: { lines: pct("LINE"), statements: pct("INSTRUCTION"), functions: pct("METHOD"), branches: pct("BRANCH") },
    };
  }
  const total = JSON.parse(text).total;
  return {
    file: cfg.coverage.summary,
    metrics: Object.fromEntries(["lines", "statements", "functions", "branches"].map((m) => [m, total[m].pct])),
  };
}
