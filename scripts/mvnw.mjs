// Doučko factory — cross-platform Maven wrapper launcher (backend repo).
// npm scripts, git hooks and qa-verify call `node scripts/mvnw.mjs <goals>` so
// the same command works in cmd.exe/PowerShell (mvnw.cmd), Git Bash and CI
// (./mvnw). Honest failure: before the Spring Boot scaffold exists there is no
// wrapper, and this exits 1 with the fix — never a silent pass.
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";

const win = process.platform === "win32";
const wrapper = win ? "mvnw.cmd" : "./mvnw";
if (!existsSync(win ? "mvnw.cmd" : "mvnw")) {
  console.error(
    "mvnw: Maven wrapper not found — the backend is not scaffolded yet. Generate it (Spring Initializr: Kotlin, Maven, Java 21) " +
      "or run `mvn -N wrapper:wrapper`, and commit mvnw, mvnw.cmd and .mvn/.",
  );
  // qa-verify reads the Result line: before any Kotlin source exists the
  // missing wrapper is pre-phase emptiness (SKIP-pending); after, NOT-EARNED.
  console.log(`Result: ${existsSync("src/main") ? "NOT-EARNED" : "SKIP-pending"}`);
  process.exit(1);
}
const r = spawnSync(wrapper, process.argv.slice(2), { stdio: "inherit", shell: win });
process.exit(r.status ?? 1);
