// Start/stop the Doučko stack on the laptop (TC-5) with the NFR-2 guard compose cannot express.
//
//   npm run stack:up                     # check the effective port bindings, build, start, wait for health
//   npm run stack:down                   # stop and remove containers (the data volume is kept)
//   node tools/stack.mjs ps              # list containers and their published ports
//   node tools/stack.mjs --check-bind <address>                 # exit 0 if allowed, 1 otherwise
//   node tools/stack.mjs --check-config [--env-file <file>]     # exit 0 if every published port is allowed
//
// The check reads the configuration compose itself renders (`docker compose config`), never a
// re-parse of .env: compose accepts `export KEY=`, lets a later duplicate win and lets the process
// environment override the file. A plain `docker compose up` skips this guard (see compose.yaml).
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { ROOT, bindAddressProblem, composeArgs, parseArgs, publishedPortProblems } from "./lib/compose.mjs";

const { opts, positional } = parseArgs(process.argv.slice(2), ["check-bind", "env-file"]);
const HINT = "Use 127.0.0.1 or the laptop's home-LAN address (NFR-2).";

if ("check-bind" in opts) {
  const problem = bindAddressProblem(opts["check-bind"]);
  if (problem) console.error(`stack: DOUCKO_BIND_ADDRESS ${problem}. ${HINT}`);
  process.exit(problem ? 1 : 0);
}

function refuseConfig() {
  const problems = publishedPortProblems(opts["env-file"]);
  for (const p of problems) console.error(`stack: refused — ${p}. Set DOUCKO_BIND_ADDRESS in .env. ${HINT}`);
  return problems.length > 0;
}

if (opts["check-config"]) process.exit(refuseConfig() ? 1 : 0);

// compose reads .env for up/down/ps; checking another file than the one it starts would be no check.
if ("env-file" in opts) {
  console.error("stack: --env-file only works with --check-config; up, down and ps always use .env.");
  process.exit(2);
}

const docker = (...args) => spawnSync("docker", [...composeArgs(), ...args], { stdio: "inherit" }).status ?? 1;
const command = positional[0];

if (command === "up") {
  // Also fails when DOUCKO_DB_PASSWORD or ADMIN_PASSWORD is unset: compose.yaml requires them (${...:?}).
  if (refuseConfig()) process.exit(1);
  const frontendDockerfile = join(ROOT, "..", "frontend", "Dockerfile");
  if (!existsSync(frontendDockerfile)) {
    console.error(`stack: ${frontendDockerfile} not found — the frontend service builds from the sibling checkout (decision D-P1).`);
    process.exit(1);
  }
  console.log("stack: every published port binds an allowed address");
  process.exit(docker("up", "-d", "--build", "--wait"));
} else if (command === "down") {
  process.exit(docker("down"));
} else if (command === "ps") {
  process.exit(docker("ps"));
} else {
  console.error("usage: node tools/stack.mjs up | down | ps | --check-bind <address> | --check-config [--env-file <file>]");
  process.exit(2);
}
