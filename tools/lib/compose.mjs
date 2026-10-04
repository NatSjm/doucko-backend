// Shared helpers for the operator tools (stack, backup, restore). Plain Node, no dependencies,
// works the same in PowerShell, cmd and Git Bash.
import { spawnSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** The backend repo root (where compose.yaml lives), whatever the caller's cwd. */
export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
export const COMPOSE_FILE = join(ROOT, "compose.yaml");

/** `docker compose` argv prefix, optionally for a separate project (tests use their own). */
export function composeArgs(project) {
  return ["compose", "-f", COMPOSE_FILE, ...(project ? ["-p", project] : [])];
}

/** Parse `--flag value`, `--switch` and positional arguments. */
export function parseArgs(argv, valueFlags) {
  const opts = {};
  const positional = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith("--")) positional.push(a);
    else if (valueFlags.includes(a.slice(2))) opts[a.slice(2)] = argv[++i];
    else opts[a.slice(2)] = true;
  }
  return { opts, positional };
}

/**
 * NFR-2 (admin-access "Local network only"): a bind address must be IPv4 loopback (127/8) or
 * RFC 1918 (10/8, 172.16/12, 192.168/16). Wildcards, public addresses, IPv6 and host names are refused.
 */
export function bindAddressProblem(address) {
  // No leading zeros: inet_aton-style parsers read `010` as octal 8.
  const octet = "(0|[1-9]\\d{0,2})";
  const octets = new RegExp(`^${octet}\\.${octet}\\.${octet}\\.${octet}$`).exec(address ?? "");
  if (!octets) return `"${address ?? ""}" is not a canonical IPv4 address`;
  const [a, b, c, d] = octets.slice(1).map(Number);
  if ([a, b, c, d].some((n) => n > 255)) return `"${address}" is not a valid IPv4 address`;
  const ok = a === 127 || a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
  return ok ? null : `"${address}" is neither loopback nor a private LAN (RFC 1918) address`;
}

/**
 * NFR-2 against what compose will actually do: render the effective configuration
 * (`docker compose config`, same env-file and interpolation rules as `up`) and check the host
 * address of every published port. A missing host address means all interfaces. Returns problems,
 * or a single entry explaining why the configuration could not be rendered.
 */
export function publishedPortProblems(envFile) {
  const args = [...composeArgs(), ...(envFile ? ["--env-file", envFile] : []), "config", "--format", "json"];
  const r = spawnSync("docker", args, { cwd: ROOT, encoding: "utf8", windowsHide: true });
  if (r.status !== 0) return [`docker compose config failed: ${(r.stderr || r.error?.message || "").trim()}`];
  const problems = [];
  for (const [service, def] of Object.entries(JSON.parse(r.stdout).services ?? {})) {
    for (const port of def.ports ?? []) {
      const problem = bindAddressProblem(port.host_ip || "0.0.0.0");
      if (problem) problems.push(`${service} port ${port.published}: ${problem}`);
    }
  }
  return problems;
}
