// Shared helpers for the operator tools (stack, backup, restore). Plain Node, no dependencies,
// works the same in PowerShell, cmd and Git Bash.
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** The backend repo root (where compose.yaml lives), whatever the caller's cwd. */
export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
export const COMPOSE_FILE = join(ROOT, "compose.yaml");

/** `docker compose` argv prefix, optionally for a separate project (tests use their own). */
export function composeArgs(project) {
  return ["compose", "-f", COMPOSE_FILE, ...(project ? ["-p", project] : [])];
}

/** Minimal .env reader (KEY=VALUE lines, # comments). Process env wins, as in docker compose. */
export function envValue(key) {
  if (process.env[key] !== undefined) return process.env[key];
  const file = join(ROOT, ".env");
  if (!existsSync(file)) return undefined;
  for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (m && m[1] === key) return m[2].replace(/^(['"])(.*)\1$/, "$2");
  }
  return undefined;
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
  const octets = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(address ?? "");
  if (!octets) return `"${address ?? ""}" is not an IPv4 address`;
  const [a, b, c, d] = octets.slice(1).map(Number);
  if ([a, b, c, d].some((n) => n > 255)) return `"${address}" is not a valid IPv4 address`;
  const ok = a === 127 || a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
  return ok ? null : `"${address}" is neither loopback nor a private LAN (RFC 1918) address`;
}
