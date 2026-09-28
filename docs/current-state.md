# Current State — backend

> Persistent handoff file for future agent windows. A quick map, not a
> replacement for source-of-truth artifacts.

## Last Updated

- **Date and time:** 2026-09-28 (Europe/Prague)
- **Current phase:** Phase 0
- **Last completed gate:** none
- **Active change:** none
- **Contract pin:** none yet — gates read the sibling `../docs` (UNPINNED warning) until `contract/` is added
- **Progress:** Harness installed. Spring Boot **4.1.1** scaffold (ADR-0003 in docs amends
  TC-1 from 3.x): Kotlin 2.3, JVM 21, Maven wrapper 3.9.16, Web MVC, Security, Validation,
  JPA, Flyway, Postgres, Actuator, Spring Modulith 2.1. Plugins: openapi-generator 7.25
  (`kotlin-spring`, `interfaceOnly`, `useSpringBoot4` → `cz.doucko.api[.model]`, an OPEN
  Modulith module), Spotless 3.10 (ktlint 1.8, reads .editorconfig), JaCoCo 0.8.15 (unit + IT
  merged → `target/site/jacoco/jacoco.xml`), Failsafe (`*IT.kt`, Testcontainers `postgres:16-alpine`).
  Tests: `ModularityTest` (Modulith verify) and `DouckoBackendApplicationIT` (context against real
  Postgres). `.env.example` added. No product code beyond the app class yet.
- **Next task:** Phase 0 step 5, pin `contract/` (`git submodule add <docs-url> contract`) once
  the docs repo has a remote. Then Phase 1 in docs.
- **Claims:**
  - Harness installed and not drifted — evidence: `npm run factory:check`
  - Scaffold builds and passes compile, ktlint, unit, IT, Modulith and JaCoCo. Evidence: `npm run verify` (exit 0, 2026-09-28)
  - G0 PASS, G1+ not started. Evidence: `npm run gate:status`
- **Scope NOT delivered:** `contract/` submodule (the docs repo has no remote), Docker Compose stack
  (Phase 5), admin security config (admin-access slice), Modulith JPA event registry (deferred, ADR-0003).

## Validation

```bash
npm run qa:verify
npm run gate:status
npx openspec doctor
```

## Agent Rules / Gotchas

- Windows: `node scripts/mvnw.mjs` picks `mvnw.cmd` / `./mvnw`.
- The machine's `java` on PATH is 1.8 and `JAVA_HOME` is JDK 26. The build works on 26, and
  `-Xjdk-release=21` keeps the API surface at 21. CI uses Temurin 21; a local JDK 21 is at
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`.
- Agent shells set `NoDefaultCurrentDirectoryInExePath=1`. Before PD-1 this made `node scripts/mvnw.mjs`
  fail ("mvnw.cmd is not recognized"). The launcher now spawns `.\mvnw.cmd`, and `npm run verify` passed
  with the variable set on 2026-09-28. No workaround needed (docs `f3ece43`, proposal `openspec/changes/improve-PD-1/`).
- Until `api/openapi.yaml` exists, Maven logs "Code generation is skipped" (profile `no-sibling-contract`).
- Boot 4 = Jackson 3 (`tools.jackson.*`; annotations stay `com.fasterxml.jackson.annotation`).
