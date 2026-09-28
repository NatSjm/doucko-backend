# Current State — backend

> Persistent handoff file for future agent windows. A quick map, not a
> replacement for source-of-truth artifacts.

## Last Updated

- **Date and time:** 2026-09-28 (Europe/Prague)
- **Current phase:** Phase 0
- **Last completed gate:** none
- **Active change:** none
- **Contract pin:** none yet — gates read the sibling `../docs` (UNPINNED warning) until `contract/` is added
- **Progress:** Delivery harness installed (`scripts/`, `.githooks/`, `.claude/`,
  `factory.config.json`); OpenSpec root initialized with `references: [doucko]`.
  No application code yet.
- **Next task:** Scaffold Spring Boot (Kotlin, Maven, Java 21) with Spotless/ktlint,
  JaCoCo XML, Failsafe + Testcontainers, Spring Modulith, openapi-generator —
  see `../docs/factory/PLAYBOOK.md` Phase 0 step 4. Then seal the integrity lock.
- **Claims:**
  - Harness installed and not drifted — evidence: `npm run factory:check`

## Validation

```bash
npm run qa:verify
npm run gate:status
npx openspec doctor
```

## Agent Rules / Gotchas

- Windows: `node scripts/mvnw.mjs` picks `mvnw.cmd` / `./mvnw`.
- The machine's default `java` is 1.8 — JDK 21 must be installed and on `JAVA_HOME`.
