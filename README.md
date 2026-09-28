# Doučko — backend

Kotlin · Spring Boot 4 · JVM 21 · Maven · Postgres 16 · Flyway — the API and
domain of **Doučko** (home math tutor). Product contract: the docs repo
(OpenSpec store `doucko`), pinned here as the `contract/` submodule.

> Status: Phase 0 — harness installed and Spring Boot 4.1 scaffold in place
> (ADR-0003). Next: pin `contract/` once the docs repo has a remote.

```bash
npm install          # tooling only: pinned OpenSpec CLI + harness scripts
npm run verify       # node scripts/mvnw.mjs -B verify
npm run qa:verify    # full battery (writes docs/qa/automated-verification-latest.md)
npm run gate:status  # G0–G8
node scripts/mvnw.mjs spring-boot:test-run   # run locally against a throwaway Postgres 16 container
```

Agents and humans: start with [AGENTS.md](AGENTS.md).
