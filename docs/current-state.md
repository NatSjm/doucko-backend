# Current State — backend

> Persistent handoff file for future agent windows. A quick map, not a
> replacement for source-of-truth artifacts.

## Last Updated

- **Date and time:** 2026-10-05 (Europe/Prague)
- **Current phase:** Phase 4
- **Last completed gate:** G3
- **Active change:** none. Slice 7 backend `implement-practice-flow-api` archived 2026-10-05 on branch `slice7-practice-flow` (not pushed, not merged).
- **Contract pin:** `contract/` submodule at docs `0168eba`, API 0.5.1
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
  Slice 4: module `cz.doucko.profile`, first Flyway
  migration `V1__child_profile.sql` (single-row `child_profile`), profile created on first start from
  `DOUCKO_CHILD_DISPLAY_NAME` (validated, startup refusal never echoes it), `getProfileSettings` /
  `updateProfileSettings` login-free with presence-aware PATCH, configured task locales (`uk`).
  Slice 5: sources, tasks, images, preview, tester, bulk publish (`V2__content_authoring.sql`). Slice 6:
  missions (`AdminMissionsApi`, `V3__missions.sql`) with the FR-57 publishing invariant, the task guards
  (CA-D6..CA-D10) and the delete rules; public API of `cz.doucko.content`: the `ServedContent` query (FR-12)
  and the `ContentUsage` port (FR-56, CA-D22). Slice 7: module `cz.doucko.practice` (`ChildHqApi`, `ChildPracticeApi`,
  login-free; `V4__practice.sql`): HQ, briefing, task screen, two attempts with locked parts and the reveal, FR-22
  UNPARSEABLE refusal, hints, uk translation, problem reports, resume, append-only attempt log, `TutorPort` with one
  deterministic tutor; implements `ContentUsage` (the slice-6 delete guards now fire); content public API gained
  `ServedContent.task/image`, profile gained `ChildProfile`. XP, ranks, summary and replay are placeholders (slice 8).
- **Next task:** slice 8 (gamification) per the capability plan: replace the slice-7 placeholders (`AttemptResult`
  `xpAwarded`/`bonusXp`/`completedMissionIds`/`rankUp`, HQ `totalXp`/`rank`/`pendingRankUp`, briefing
  `xpAvailable`, `MissionSummary` XP and ranks, `ackRankUp`) and own the replay and summary rules (practice runs and
  scopes are in `cz.doucko.practice`, design in `openspec/changes/archive/*implement-practice-flow-api/design.md`).
- **Claims:**
  - Slice 7 QA battery passes (627 unit, 89 integration tests incl. `PracticeIT`, `PracticeTimingIT` (NFR-5 attempt round trip p95 173 ms in-process), `PracticeLogCaptureIT`) — evidence: `docs/qa/automated-verification-latest.md` (2026-10-05)
  - Slice 7 review gate clean (9 findings: 4 major, 5 minor; 8 fixed in `6befcf1`, 1 contested minor waived) — evidence: `openspec/changes/archive/2026-10-05-implement-practice-flow-api/review-findings.json`
  - Slice 7 RED run — evidence: `docs/qa/evidence/slice7-red-run.md`; real-stack smoke PASS after the merge (2026-10-05) — evidence: `docs/qa/evidence/slice7-compose-smoke.md`
  - Slice 6 QA battery passes (605 unit, 52 integration tests incl. `MissionsIT`, `MissionApiTest`, `MissionRulesTest`) — evidence: `docs/qa/automated-verification-latest.md` (2026-10-04)
  - Slice 6 review gate clean (6 findings: 1 major, 5 minor, all fixed in `07518f9`) — evidence: `openspec/changes/archive/2026-10-04-implement-authoring-missions-api/review-findings.json`
  - Slice 6 RED run — evidence: `docs/qa/evidence/slice6-red-run.md`; real-stack smoke PASS after the merge (2026-10-05) — evidence: `docs/qa/evidence/slice6-compose-smoke.md`
  - Slice 5 QA battery passes (548 unit, 44 integration tests incl. `ContentAuthoringIT`, `UploadLimitsIT`) — evidence: `docs/qa/automated-verification-latest.md` (2026-10-04)
  - Slice 5 review gate clean (14 findings: 11 fixed in `3738bfe`, 3 waived) — evidence: `openspec/changes/archive/2026-10-04-implement-authoring-tasks-api/review-findings.json`
  - Slice 5 RED run — evidence: `docs/qa/evidence/slice5-red-run.md`; real-stack smoke PASS after the merge (2026-10-04) — evidence: `docs/qa/evidence/slice5-compose-smoke.md`
  - Slice 3 QA battery passes (347 unit, 25 integration tests; 217 golden cases, checker p95 37–73 µs; review gate 14 findings dispositioned) — evidence: `docs/qa/automated-verification-latest.md`, `docs/qa/evidence/slice3-checker-run.md` (2026-10-04)
  - Slice 3 review gate clean (14 findings: 8 fixed in `f26150d` and `6343ca7`, 6 waived with reasons) — evidence: `openspec/changes/archive/2026-10-04-implement-answer-checking-api/review-findings.json`
  - Slice 3 RED run — evidence: `docs/qa/evidence/slice3-red-run.md`
  - Slice 4 QA battery passes (194 unit, 33 integration tests incl. `ProfileSettingsIT`, `ChildProfileStartupIT`) — evidence: `docs/qa/automated-verification-latest.md` (2026-10-04)
  - Slice 4 review gate clean (8 findings: 6 fixed in `de80c7a`, 2 waived) — evidence: `openspec/changes/archive/2026-10-04-implement-child-profile-api/review-findings.json`
  - Slice 4 RED run — evidence: `docs/qa/evidence/slice4-red-run.md`; real-stack smoke PASS after the merge — evidence: `docs/qa/evidence/slice4-compose-smoke.md`
  - Slice 2 QA battery passes (108 unit, 25 integration tests incl. `AdminSessionIT`) — evidence: `docs/qa/automated-verification-latest.md` (2026-10-04)
  - Slice 2 RED run — evidence: `docs/qa/evidence/slice2-red-run.md`; real-stack smoke — evidence: `docs/qa/evidence/slice2-compose-smoke.md`
  - QA battery passes — evidence: `docs/qa/automated-verification-latest.md` (2026-10-04)
  - Real-stack smoke (task 4.4) — evidence: `docs/qa/evidence/slice1-compose-smoke.md`
  - Review gate clean (13 findings, all fixed in `d2cb340`) — evidence:
    `openspec/changes/archive/*implement-platform-foundation-api/review-findings.json`
  - G0, G2, G4, G5, G6, G8 PASS (G4 audits the archived slice); G1 and G3 need the owner's sign-off; G7 (release) fails until every
    MVP slice is archived. Evidence: `npm run gate:status`
- **Scope NOT delivered:** slice 7: XP, ranks, rank-ups, mission summary XP and the gamification rules of replay (slice 8; the contract fields are placeholders: 0 / [] / null, first rank of a placeholder ladder); the Compose-stack NFR-5 measurement and task-screen load time, NFR-7 and the UI scenarios (frontend half); stats, flag queue and re-evaluation over the stored reports and attempt log (slice 9); slice 5: child serving (slice 7), backup of the `doucko-storage` volume (NFR-10 covers only Postgres), the real-stack smoke; the answer-checking endpoint rules (FR-22 whole-submission refusal, answer tester, re-evaluation: slices 5, 7, 9); slice 4 parts that need later slices or other repos (HQ `displayName`, XP/resolved tasks surviving a restart, e2e profile count, translation toggle, privacy scan, recordings; see `openspec/changes/archive/2026-10-04-implement-child-profile-api/design.md` Non-goals); the product capabilities still open (gamification,
  admin-access, stats); Modulith JPA event registry (deferred, ADR-0003); the round-3 review fixes
  were not re-confirmed by a further review round (owner's choice).

## Validation

```bash
npm run qa:verify
npm run gate:status
npx openspec doctor
```

## Agent Rules / Gotchas

- `npm run restore` of a dump taken before a newer migration drops that migration's tables under the
  running backend (Flyway only migrates at startup). Run `docker compose restart backend` after such a
  restore. The restore never touches the `doucko-storage` files (seen in the slice 7 smoke, 2026-10-05).

- On a first start against an empty database the backend refuses to start without a valid
  `DOUCKO_CHILD_DISPLAY_NAME` (1–50 characters). Tests use the fictional `Hrdinka`.
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
