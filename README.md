# Doučko — backend

Kotlin · Spring Boot 3 · JVM 21 · Maven · Postgres 16 · Flyway — the API and
domain of **Doučko** (home math tutor). Product contract: the docs repo
(OpenSpec store `doucko`), pinned here as the `contract/` submodule.

> Status: Phase 0 — delivery harness installed; the Spring Boot scaffold is
> the next step (see `../docs/factory/PLAYBOOK.md`, Phase 0 step 4).

```bash
npm install          # tooling only: pinned OpenSpec CLI + harness scripts
npm run verify       # node scripts/mvnw.mjs -B verify
npm run qa:verify    # full battery (writes docs/qa/automated-verification-latest.md)
npm run gate:status  # G0–G8
```

Agents and humans: start with [AGENTS.md](AGENTS.md).
