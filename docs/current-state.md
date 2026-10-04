# Current State — backend

> Persistent handoff file for future agent windows. A quick map, not a
> replacement for source-of-truth artifacts.

## Last Updated

- **Date and time:** 2026-10-04 (Europe/Prague)
- **Current phase:** Phase 4
- **Last completed gate:** G3
- **Active change:** none. Slice 3 `implement-answer-checking-api` archived 2026-10-04 (branch `slice3-answer-checking`, not pushed or merged).
- **Contract pin:** `contract/` submodule (`url = ../docs`) at docs `4c87d67`, API 0.5.1
- **Progress:** Spring Boot 4.1.1 / Kotlin 2.3 / JVM 21 scaffold (ADR-0003) plus slice 1 platform
  foundation: Compose stack (db, backend, frontend; only the frontend's 8081 published, on
  `DOUCKO_BIND_ADDRESS`, checked by `npm run stack:up`), backup/restore tooling, problem+json for
  undeclared paths and refusals, actuator health only,
  request content never logged (log guard incl. Tomcat parser), DNS-rebinding Host allowlist
  (`StrictHttpFirewall`), fixture provenance test. Slice 2: module `cz.doucko.admin` with
  its own chain for `/api/admin/**` (sign-in, session check, sign-out, CSRF, 401 before 403, 8 h idle
  expiry on an injectable `Clock`, startup password policy). Slice 3: pure module `cz.doucko.checking`
  (`AnswerChecker.check`, no Spring or DB) for all seven answer types, exact rational arithmetic, tolerance,
  alternatives and Czech format tips from `messages.properties`; 217-case golden table.
- **Next task:** slice 4 (child-profile) or 5 (authoring-tasks) per the capability plan; the merge order of the slice branches is the owner's call.
- **Claims:**
  - Slice 3 QA battery passes (347 unit, 25 integration tests; 217 golden cases, checker p95 37–73 µs; review gate 14 findings dispositioned) — evidence: `docs/qa/automated-verification-latest.md`, `docs/qa/evidence/slice3-checker-run.md` (2026-10-04)
  - Slice 3 review gate clean (14 findings: 8 fixed in `f26150d` and `6343ca7`, 6 waived with reasons) — evidence: `openspec/changes/archive/2026-10-04-implement-answer-checking-api/review-findings.json`
  - Slice 3 RED run — evidence: `docs/qa/evidence/slice3-red-run.md`
  - Slice 2 QA battery passes (108 unit, 25 integration tests incl. `AdminSessionIT`) — evidence: `docs/qa/automated-verification-latest.md` (2026-10-04)
  - Slice 2 RED run — evidence: `docs/qa/evidence/slice2-red-run.md`; real-stack smoke — evidence: `docs/qa/evidence/slice2-compose-smoke.md`
  - QA battery passes — evidence: `docs/qa/automated-verification-latest.md` (2026-10-04)
  - Real-stack smoke (task 4.4) — evidence: `docs/qa/evidence/slice1-compose-smoke.md`
  - Review gate clean (13 findings, all fixed in `d2cb340`) — evidence:
    `openspec/changes/archive/*implement-platform-foundation-api/review-findings.json`
  - G0, G2, G4, G5, G6, G8 PASS (G4 audits the archived slice); G1 and G3 need the owner's sign-off; G7 (release) fails until every
    MVP slice is archived. Evidence: `npm run gate:status`
- **Scope NOT delivered:** the answer-checking endpoint rules (FR-22 whole-submission refusal, answer tester, re-evaluation: slices 5, 7, 9); the "Child write is limited to its own practice" scenario (needs slice 7 attempts); every product capability after slice 1 (authoring, practice, gamification,
  admin-access, stats); Modulith JPA event registry (deferred, ADR-0003); the round-3 review fixes
  were not re-confirmed by a further review round (owner's choice).

## Validation

```bash
npm run qa:verify
npm run gate:status
npx openspec doctor
```

## Agent Rules / Gotchas

- The backend refuses to start without `ADMIN_PASSWORD` (12 characters to 72 bytes). `compose.yaml`
  requires it, so add it to `.env` before `npm run stack:up`. Tests get the spec example value from
  `src/test/resources/application.properties`.

- Windows: `node scripts/mvnw.mjs` picks `mvnw.cmd` / `./mvnw`.
- The machine's `java` on PATH is 1.8 and `JAVA_HOME` is JDK 26. The build works on 26, and
  `-Xjdk-release=21` keeps the API surface at 21. CI uses Temurin 21; a local JDK 21 is at
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`.
- Agent shells set `NoDefaultCurrentDirectoryInExePath=1`. Before PD-1 this made `node scripts/mvnw.mjs`
  fail ("mvnw.cmd is not recognized"). The launcher now spawns `.\mvnw.cmd`, and `npm run verify` passed
  with the variable set on 2026-09-28. No workaround needed (docs `f3ece43`, proposal `openspec/changes/improve-PD-1/`).
- Until `api/openapi.yaml` exists, Maven logs "Code generation is skipped" (profile `no-sibling-contract`).
- Boot 4 = Jackson 3 (`tools.jackson.*`; annotations stay `com.fasterxml.jackson.annotation`).
