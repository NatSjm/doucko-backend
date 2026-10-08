// Isolated end-to-end stack for the frontend's real-stack Playwright suite (Phase 5).
//
//   node tools/e2e-stack.mjs up      # NFR-2 check, build, start, wait for health (http://127.0.0.1:18082)
//   node tools/e2e-stack.mjs seed    # idempotent self-authored seed through the admin API
//   node tools/e2e-stack.mjs down    # remove containers AND the throwaway volumes of doucko-e2e
//   node tools/e2e-stack.mjs ps
//   add --dry-run to print the docker argv as JSON instead of running it
//
// Always Compose project `doucko-e2e` (never the owner's `doucko`), compose.yaml + compose.e2e.yaml,
// and the committed fake test values in tools/e2e-stack.env (never .env). The frontend builds from
// DOUCKO_FRONTEND_DIR (default ../frontend).
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { ROOT, bindAddressProblem } from "./lib/compose.mjs";
import {
  E2E_BASE_URL,
  E2E_ENV_FILE,
  E2E_PORT,
  composeEnv,
  dockerArgsFor,
  e2eAdminPassword,
  e2eComposeArgs,
  leakedEnv,
  parseCommand,
} from "./lib/e2e-stack.mjs";
import { AdminClient, seed } from "./lib/e2e-seed.mjs";

const parsed = parseCommand(process.argv.slice(2));
if (parsed.error) {
  console.error(`e2e-stack: ${parsed.error}\nusage: node tools/e2e-stack.mjs up | down | seed | ps [--dry-run]`);
  process.exit(2);
}
const { command, dryRun } = parsed;
const env = composeEnv(process.env);

if (dryRun) {
  console.log(
    JSON.stringify({
      command,
      docker: dockerArgsFor(command),
      frontendDir: env.DOUCKO_FRONTEND_DIR,
      baseUrl: E2E_BASE_URL,
      leakedEnv: leakedEnv(env),
    }),
  );
  process.exit(0);
}

const run = (args) => spawnSync("docker", args, { cwd: ROOT, env, stdio: "inherit" }).status ?? 1;

/** NFR-2 on what compose will really publish: only the frontend, only on 127.0.0.1:18082. */
function portProblems() {
  const r = spawnSync("docker", [...e2eComposeArgs(), "config", "--format", "json"], { cwd: ROOT, env, encoding: "utf8", windowsHide: true });
  if (r.status !== 0) return [`docker compose config failed: ${(r.stderr || r.error?.message || "").trim()}`];
  const problems = [];
  for (const [service, def] of Object.entries(JSON.parse(r.stdout).services ?? {})) {
    for (const port of def.ports ?? []) {
      const problem = bindAddressProblem(port.host_ip || "0.0.0.0");
      if (problem) problems.push(`${service} port ${port.published}: ${problem}`);
      if (service !== "frontend") problems.push(`${service} must not be published (port ${port.published})`);
      else if (port.host_ip !== "127.0.0.1" || String(port.published) !== String(E2E_PORT)) {
        problems.push(`frontend must publish only 127.0.0.1:${E2E_PORT}, not ${port.host_ip || "*"}:${port.published}`);
      }
    }
  }
  return problems;
}

if (command === "up") {
  if (!existsSync(join(env.DOUCKO_FRONTEND_DIR, "Dockerfile"))) {
    console.error(`e2e-stack: no Dockerfile in ${env.DOUCKO_FRONTEND_DIR} — set DOUCKO_FRONTEND_DIR to a frontend checkout.`);
    process.exit(1);
  }
  const problems = portProblems();
  for (const p of problems) console.error(`e2e-stack: refused — ${p} (NFR-2)`);
  if (problems.length) process.exit(1);
  console.log(`e2e-stack: building frontend from ${env.DOUCKO_FRONTEND_DIR}`);
  const status = run(dockerArgsFor("up"));
  if (status === 0) console.log(`e2e-stack: up at ${E2E_BASE_URL}`);
  process.exit(status);
} else if (command === "down" || command === "ps") {
  process.exit(run(dockerArgsFor(command)));
} else if (command === "seed") {
  try {
    const client = new AdminClient(E2E_BASE_URL);
    await client.signIn(e2eAdminPassword(readFileSync(E2E_ENV_FILE, "utf8")));
    const result = await seed(client, (m) => console.log(`e2e-stack: ${m}`));
    console.log(`e2e-stack: seeded ${JSON.stringify(result)}`);
  } catch (e) {
    console.error(`e2e-stack: seed failed — ${e.message}`);
    process.exit(1);
  }
}
