// Pure parts of tools/e2e-stack.mjs: project-name guard, argv, environment and compose arguments.
// No Docker calls here, so OperatorToolGuardTest can check them without a daemon.
import { isAbsolute, join, resolve } from "node:path";
import { ROOT } from "./compose.mjs";

/** The only Compose project this tool ever addresses. Never the owner's `doucko`. */
export const E2E_PROJECT = "doucko-e2e";
export const E2E_PORT = 18082;
export const E2E_BASE_URL = `http://127.0.0.1:${E2E_PORT}`;
export const E2E_ENV_FILE = join(ROOT, "tools", "e2e-stack.env");
export const E2E_OVERRIDE_FILE = join(ROOT, "compose.e2e.yaml");
export const COMMANDS = ["up", "down", "seed", "ps"];

/**
 * Refuses every project name except `doucko-e2e`. `down -v` deletes volumes, so a wrong name
 * here would delete the owner's real data (project `doucko`).
 */
export function assertE2eProject(name) {
  if (name !== E2E_PROJECT) {
    throw new Error(`e2e-stack: refusing Compose project "${name}" — this tool only manages "${E2E_PROJECT}"`);
  }
  return name;
}

/** `{ command, dryRun }` or `{ error }` for an unknown or missing command / flag. */
export function parseCommand(argv) {
  const flags = argv.filter((a) => a.startsWith("--"));
  const positional = argv.filter((a) => !a.startsWith("--"));
  const unknownFlag = flags.find((f) => f !== "--dry-run");
  if (unknownFlag) return { error: `unknown option ${unknownFlag}` };
  if (positional.length !== 1 || !COMMANDS.includes(positional[0])) {
    return { error: `expected one of ${COMMANDS.join(" | ")}` };
  }
  return { command: positional[0], dryRun: flags.includes("--dry-run") };
}

/** Absolute frontend build context: DOUCKO_FRONTEND_DIR (relative to the backend root) or ../frontend. */
export function frontendDir(env) {
  const raw = (env.DOUCKO_FRONTEND_DIR ?? "").trim() || "../frontend";
  return isAbsolute(raw) ? resolve(raw) : resolve(ROOT, raw);
}

/**
 * Environment for `docker compose`: the process environment would win over --env-file, so every
 * value the e2e env file sets (and the project / file selectors) is removed; the frontend dir is
 * passed resolved.
 */
export function composeEnv(env) {
  const out = { ...env };
  for (const key of [
    "COMPOSE_PROJECT_NAME",
    "COMPOSE_FILE",
    "COMPOSE_ENV_FILES",
    "COMPOSE_PROFILES",
    "DOUCKO_DB_PASSWORD",
    "ADMIN_PASSWORD",
    "DOUCKO_CHILD_DISPLAY_NAME",
    "DOUCKO_BIND_ADDRESS",
  ]) {
    delete out[key];
  }
  out.DOUCKO_FRONTEND_DIR = frontendDir(env);
  return out;
}

/** `docker` argv prefix: always the e2e project, both compose files and the committed test env file. */
export function e2eComposeArgs(project = E2E_PROJECT) {
  return [
    "compose",
    "-p",
    assertE2eProject(project),
    "-f",
    join(ROOT, "compose.yaml"),
    "-f",
    E2E_OVERRIDE_FILE,
    "--env-file",
    E2E_ENV_FILE,
  ];
}

/** The docker argv for each lifecycle command (seed talks HTTP, not docker). */
export function dockerArgsFor(command) {
  const base = e2eComposeArgs();
  if (command === "up") return [...base, "up", "-d", "--build", "--wait", "--wait-timeout", "600"];
  if (command === "down") return [...base, "down", "-v", "--remove-orphans"];
  if (command === "ps") return [...base, "ps"];
  return null;
}

/** Reads ADMIN_PASSWORD from the committed test env file (KEY=VALUE lines). */
export function e2eAdminPassword(text) {
  const line = text.split(/\r?\n/).find((l) => l.startsWith("ADMIN_PASSWORD="));
  if (!line) throw new Error("e2e-stack: ADMIN_PASSWORD missing in tools/e2e-stack.env");
  return line.slice("ADMIN_PASSWORD=".length).trim();
}
