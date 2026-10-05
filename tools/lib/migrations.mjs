// Flyway versions for the operator tools: which migration this checkout ends at, and how two
// versions compare. Plain Node, no dependencies.
import { readdirSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "./compose.mjs";

export const MIGRATIONS_DIR = join(ROOT, "src", "main", "resources", "db", "migration");

/** `V4__practice.sql` → "4", `V1_2__x.sql` → "1.2" (Flyway reads `_` in the version as `.`); else null. */
export function migrationVersion(fileName) {
  const m = /^V(\d+(?:[._]\d+)*)__.+\.sql$/.exec(fileName);
  return m ? m[1].replace(/_/g, ".") : null;
}

/** Compares dotted numeric versions part by part, so "10" > "9" and "1.10" > "1.9". */
export function compareVersions(a, b) {
  const pa = a.split(".").map(Number);
  const pb = b.split(".").map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return Math.sign(d);
  }
  return 0;
}

/** The highest of the given versions, or null for none. */
export function maxVersion(versions) {
  return versions.reduce((max, v) => (max === null || compareVersions(v, max) > 0 ? v : max), null);
}

/** The newest versioned migration in this checkout, or null when there is none. */
export function latestMigration(dir = MIGRATIONS_DIR) {
  return maxVersion(readdirSync(dir).map(migrationVersion).filter((v) => v !== null));
}
