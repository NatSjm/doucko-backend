# Current State — backend

> Persistent handoff file for future agent windows. A quick map, not a
> replacement for source-of-truth artifacts.

## Last Updated

- **Date and time:** 2026-10-09 (Europe/Prague)
- **Current phase:** Phase 7
- **Last completed gate:** G3
- **Active change:** none. Phase 7 backend `release-review-api` archived 2026-10-09 on branch `phase7-release` (not merged): contract pin bumped to docs `8908669` (API unchanged), whole-history review gate in four module runs (30 findings: major F11 NUL in child input → raw 500 fixed test-first, red `f7a7cfd`, green `aedd85f`; F06 content data errors → 400 fixed there; 28 minors waived with reasons), trajectory-eval run (44 judgements, 35 pass). **Product code changed** in `practice` and `content` (NUL refused as 400) — the frontend real-stack evidence needs a re-run against this backend. Before it: Phase 5 backend `harden-phase5-api` archived 2026-10-08 on branch `phase5-hardening` (not merged into `main` yet): isolated e2e stack `tools/e2e-stack.mjs up|down|seed|ps` (Compose project `doucko-e2e`, frontend only on `127.0.0.1:18082`, throwaway volumes, fake test values in `tools/e2e-stack.env`, idempotent PII-free seed through the admin API; `DOUCKO_FRONTEND_DIR` picks the frontend checkout) and behavioural tests that ratcheted coverage up. Before it: Slice 9 backend `implement-progress-stats-api` (the last MVP slice) archived 2026-10-08 and merged into `main` (`e40423d`) with the frontend half; real-stack smoke PASS after the merge (2026-10-08: flag queue OPEN→re-evaluate no change→fix spec→re-evaluate +4 XP FIXED, dismiss, 409/404/400/401/403 paths, stats weeks and first-try rate, both admin screens rendered from the stack, clean log; restore of the pre-smoke V5 dump restarted the V6 backend itself). Before it: slice 8 `implement-gamification-api`, merged into `main` (`9527006`) with a real-stack smoke PASS.
- **Contract pin:** `contract/` submodule at docs `8908669`, API 0.5.1
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
  `ServedContent.task/image`, profile gained `ChildProfile`. Slice 8 (gamification, in `cz.doucko.practice`; `V5__gamification.sql`): append-only XP ledger
  (`xp_award`, a trigger refuses UPDATE/DELETE; FR-38), task awards with hint cost and minimum, completion bonuses for
  every published mission a resolution completes and for a mission published (or edited) already complete (content
  event `PublishedMissionChanged`, query `ServedContent.missionsContaining`; OQ-15, OQ-28), the rank ladder, one-shot
  rank-ups (`AttemptResult.rankUp`, `HqView.pendingRankUp`, `MissionSummary.rankUp`/`rankReachedDuringRun`,
  `ackRankUp`, `rank_celebration`), briefing `xpAvailable`, summary XP for first runs and replays, and a startup
  reconciliation that credits practice stored before the ledger existed. XP values and the rank ladder come from
  `src/main/resources/gamification.json` (or `doucko.gamification.config`), validated strictly at startup.
  Slice 9 (progress-stats, in `cz.doucko.practice`; `V6__progress_stats.sql`): `AdminStatsApi` — `getStats` (sessions
  split at gaps over 30 minutes, qualifying sessions of 10+ tasks, hints and reveals per Europe/Prague week incl. DST,
  the success metric over the 4 completed weeks, first-try counts per topic after re-evaluation), `listReports` (OPEN
  first, oldest first, exact status filter, attempt as logged), `reevaluateReport` (re-checks the attached attempt with
  the current spec; only an improved first-resolution outcome changes anything: append-only `attempt_reevaluation`,
  a `REEVALUATION` ledger row with the XP difference, completion bonuses, rank-ups left pending for HQ; replay attempts
  recorded at 0 XP) and `dismissReport`. Practice state reads re-evaluated verdicts of first play-through attempts.
  Content gained the public query `ContentCatalog`.
- **Next task:** merge `phase7-release` into `main` with the frontend half, re-run the frontend real-stack e2e against the new backend, then the docs G7 `check-workspace --release --strict-tests` and the owner's laptop deploy with the `deploy-gated` items recorded. G1/G3 owner sign-offs are recorded in docs (`docs/docs/qa/checkpoint-*-signoff.md`) but gate-status here still reads them as "needs sign-off", so the header stays at G3.
- **Claims:**
  - Phase 7 G7 commands all exit 0 on `phase7-release` (2026-10-09: qa:verify with 813 unit and 163 integration tests, traceability --release --strict-tests, --check-fresh, trajectory --release --check-fresh, process-ratchet --release, factory-integrity, correct --check) — evidence: `docs/qa/automated-verification-latest.md`
  - Release review gate dispositioned (30 findings, 1 major + 1 minor fixed, 28 minor waived) — evidence: `openspec/changes/archive/2026-10-09-release-review-api/review-findings.json`, red/green `docs/qa/evidence/release-red-run.md`
  - Trajectory eval (process-order 60.8, test-integrity 84.5, in-scope 80.8, craft 83; 9 historic judgements below 70) — evidence: `docs/qa/trajectory-eval-report.md`
  - G5 PASS per `npm run gate:status` (2026-10-08, after `check-coverage-ratchet --update`: lines 99.30, statements 97.86, functions 97.74, branches 91.14; was 99.27/97.76/97.74/90.94) — evidence: `docs/qa/evidence/phase5-e2e-stack.md`. A full `mvnw verify` on this branch before any change already met the old baseline exactly (statements 97.76, branches 90.94); the reported drop to 97.48/89.95 was not reproduced.
  - Phase 5 QA battery passes (813 unit, 161 integration tests incl. `ModularityTest` and the Testcontainers ITs of every module) — evidence: `docs/qa/automated-verification-latest.md` (2026-10-08)
  - Isolated e2e stack proof PASS (up, seed twice, one mission through 127.0.0.1:18082, down removed both `doucko-e2e` volumes, project `doucko` identical before/after) — evidence: `docs/qa/evidence/phase5-e2e-stack.md`
  - Phase 5 review gate dispositioned (21 findings: 1 critical, 7 major, 13 minor, all fixed in `d3081de`; run capped at $10, 13 verify steps skipped) — evidence: `openspec/changes/archive/2026-10-08-harden-phase5-api/review-findings.json`
  - Slice 9 merged into `main` (`e40423d`); real-stack smoke PASS after the merge (2026-10-08: flag queue OPEN→re-evaluate no change→fix spec→re-evaluate +4 XP FIXED, dismiss, 409/404/400/401/403 paths, stats weeks and first-try rate, both admin screens rendered from the stack, clean log; restore of the pre-smoke V5 dump restarted the V6 backend itself) — evidence: `docs/qa/evidence/slice9-compose-smoke.md`. Open: the smoke PDF on `doucko_doucko-storage` and a stray empty `doucko-storage` volume, left for the owner to remove.
  - Slice 9 QA battery passes after the review fixes (800 unit, 161 integration tests incl. `ProgressStatsIT`, `FlagQueueIT`, `PracticeLogCaptureIT` with the four admin endpoints, `PracticeTimingIT` NFR-5 p95 78 ms) — evidence: `docs/qa/automated-verification-latest.md` (2026-10-07)
  - Slice 9 review gate dispositioned (8 findings: 1 major, 7 minor; 6 fixed in `2dfbb77`, 2 minor waived — paging and a "why no change" reason need a contract change; run cost $8.81) — evidence: `openspec/changes/archive/2026-10-08-implement-progress-stats-api/review-findings.json`
  - Slice 9 RED run — evidence: `docs/qa/evidence/slice9-red-run.md`
  - Slice 8 merged into `main` (`9527006`); real-stack smoke PASS after the merge (2026-10-07: XP table, UNPARSEABLE neutral, completion and publish-time bonus, rank-up offered by summary and HQ and acknowledged, replay at 0 XP, 405 for HQ writes, 404/409 summaries, HQ and summary rendered from the stack; restore of the pre-smoke V4 dump restarted the V5 backend itself) — evidence: `docs/qa/evidence/slice8-compose-smoke.md`
  - Slice 8 QA battery passes after the review fixes (755 unit, 124 integration tests incl. `GamificationIT`, `GamificationConfigIT`, `PracticeTimingIT` NFR-5 p95 78 ms) — evidence: `docs/qa/automated-verification-latest.md` (2026-10-07)
  - Slice 8 review gate dispositioned (6 findings: 2 major, 4 minor, all fixed in `33f5bf2`; run cost $8.88) — evidence: `openspec/changes/archive/2026-10-07-implement-gamification-api/review-findings.json`
  - Slice 8 RED run — evidence: `docs/qa/evidence/slice8-red-run.md`
  - `fix-restore-schema-drift`: QA battery passes after the review fixes (628 unit, 93 integration incl. `BackupRestoreIT` 8 tests) — evidence: `docs/qa/automated-verification-latest.md` (2026-10-05); RED, GREEN and the smoke replay — evidence: `docs/qa/evidence/fix-restore-schema-drift.md`; review gate dispositioned (17 findings: 15 fixed in `13e1f9b`, 2 minor waived; run capped at $10, 3 verify steps skipped) — evidence: `openspec/changes/archive/2026-10-05-fix-restore-schema-drift/review-findings.json`
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
- **Scope NOT delivered:** slice 9: the real-stack smoke (NOT RUN; the coordinator runs it after the merge, `docs/qa/evidence/slice9-compose-smoke.md`); the frontend half (stats page, flag queue UI, empty states, the "—" rendering, a11y and ⟦ gates, e2e for FR-40 and FR-41); flag-queue paging and a reason for "no change" (not in API 0.5.1; review F03 and F05 waived); limiting report creation on the child side (not specified); a replay re-evaluation counts as better only when the attempt is correct as a whole (design decision 8; store-spec wording to clarify in the docs store); slice 8: the rank-up celebration played in a browser on the real stack (covered by the frontend e2e and vision-verify against the stub); final rank names (placeholders, OQ-1); the store spec does not state the 50-character rank-name limit (backend implementation bound); slice 7: the Compose-stack NFR-5 measurement and task-screen load time, NFR-7 and the UI scenarios (frontend half); slice 5: child serving (slice 7), backup of the `doucko-storage` volume (NFR-10 covers only Postgres), the real-stack smoke; the answer-checking endpoint rules (FR-22 whole-submission refusal, answer tester, re-evaluation: slices 5, 7, 9); slice 4 parts that need later slices or other repos (HQ `displayName`, XP/resolved tasks surviving a restart, e2e profile count, translation toggle, privacy scan, recordings; see `openspec/changes/archive/2026-10-04-implement-child-profile-api/design.md` Non-goals); the product capabilities still open (admin-access); Modulith JPA event registry (deferred, ADR-0003); the round-3 review fixes
  were not re-confirmed by a further review round (owner's choice).

## Validation

```bash
npm run qa:verify
npm run gate:status
npx openspec doctor
```

## Agent Rules / Gotchas

- `npm run restore` of a dump taken before a newer migration drops that migration's tables (Flyway only
  migrates at startup; seen in the slice 7 smoke, 2026-10-05). Since `fix-restore-schema-drift` the restore
  compares the restored Flyway version with `src/main/resources/db/migration` and, if the backend is running,
  restarts it and waits until it is healthy and migrated. If that fails it exits 1 and prints
  `docker compose restart backend` — the data is restored either way. The restore never touches the
  `doucko-storage` files and says so; remove smoke uploads from that volume by hand.

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
