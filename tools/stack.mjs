// Start/stop the Doučko stack on the laptop (TC-5) with the NFR-2 guard compose cannot express.
//
//   npm run stack:up                     # validate DOUCKO_BIND_ADDRESS, build, start, wait for health
//   npm run stack:down                   # stop and remove containers (the data volume is kept)
//   node tools/stack.mjs ps              # list containers and their published ports
//   node tools/stack.mjs --check-bind <address>   # exit 0 if allowed, 1 otherwise
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { ROOT, bindAddressProblem, composeArgs, envValue, parseArgs } from "./lib/compose.mjs";

const { opts, positional } = parseArgs(process.argv.slice(2), ["check-bind"]);

function refuseBind(address) {
  const problem = bindAddressProblem(address);
  if (!problem) return false;
  console.error(`stack: DOUCKO_BIND_ADDRESS ${problem}. Use 127.0.0.1 or the laptop's home-LAN address (NFR-2).`);
  return true;
}

if ("check-bind" in opts) process.exit(refuseBind(opts["check-bind"]) ? 1 : 0);

const docker = (...args) => spawnSync("docker", [...composeArgs(), ...args], { stdio: "inherit" }).status ?? 1;
const command = positional[0];

if (command === "up") {
  const address = envValue("DOUCKO_BIND_ADDRESS") ?? "127.0.0.1";
  if (refuseBind(address)) process.exit(1);
  if (!envValue("DOUCKO_DB_PASSWORD")) {
    console.error("stack: DOUCKO_DB_PASSWORD is not set — copy .env.example to .env and fill it in.");
    process.exit(1);
  }
  const frontendDockerfile = join(ROOT, "..", "frontend", "Dockerfile");
  if (!existsSync(frontendDockerfile)) {
    console.error(`stack: ${frontendDockerfile} not found — the frontend service builds from the sibling checkout (decision D-P1).`);
    process.exit(1);
  }
  console.log(`stack: binding published ports to ${address}`);
  process.exit(docker("up", "-d", "--build", "--wait"));
} else if (command === "down") {
  process.exit(docker("down"));
} else if (command === "ps") {
  process.exit(docker("ps"));
} else {
  console.error("usage: node tools/stack.mjs up | down | ps | --check-bind <address>");
  process.exit(2);
}
