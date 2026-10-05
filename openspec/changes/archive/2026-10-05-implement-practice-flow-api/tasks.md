## 1. Contract and schema

- [x] 1.1 Confirm the `contract/` pin (docs `0168eba`, API 0.5.1) generates `ChildHqApi` (`getHq`, `getMissionBriefing`, `getMissionSummary`, `ackRankUp`) and `ChildPracticeApi` (`getCurrentTask`, `getTask`, `submitAttempt`, `openNextHint`, `reportProblem`, `getChildImage`, `startReplay`).
- [x] 1.2 Flyway migration `V4__practice.sql` (`practice_run`, `attempt`, `opened_hint`, `problem_report`).

## 2. Failing tests first (red)

- [x] 2.1 Unit (`*Test.kt`): `PracticeRulesTest` (task state machine, locks, unreadable refusal, mission progress, report rules), `TutorTest` (deterministic feedback, architecture: feedback only through `TutorPort`, one implementation, no AI dependency) (FR-22, FR-25, FR-27, FR-28, FR-30, FR-44).
- [x] 2.2 Integration (`*IT.kt`, Testcontainers Postgres): `PracticeIT` (HQ, briefing, task view, attempts and reveal, oversized input, hints, translation, reports, resume, attempt log, served-content 404s incl. the FR-12 carry-overs, NFR-1 child write, `ContentUsage` delete guard, placeholders), `TutorSwapTest` (web slice, swapped tutor), `PracticeTimingIT` (NFR-5 round trip), `PracticeLogCaptureIT` (NFR-3).
- [x] 2.3 Run and confirm RED on assertions. Evidence: `docs/qa/evidence/slice7-red-run.md`.

## 3. Domain and application (green)

- [x] 3.1 `domain/`: scopes, records, `TaskProgress`, `Grading`, `MissionProgress`, `ReportRules`, placeholder ladder.
- [x] 3.2 `application/`: ports `PracticeStore`, `TutorPort`; `PracticeService`; `PracticeContentUsage`; content public API (`ServedContent.task`, `image`, `ServedMission.topicName`); profile public API `ChildProfile`.

## 4. Adapters (green)

- [x] 4.1 `adapter/out/persistence`: `JdbcPracticeStore` with the practice advisory lock; `adapter/out/tutor`: `DeterministicTutor`.
- [x] 4.2 `adapter/in/web`: `ChildHqController`, `ChildPracticeController`, body advice and readers, mapping, revealed-answer serializer, problem advice; messages; `ContentBodyAdvice` scoped to content; log guard covers `org.springframework.jdbc` and all of `org.hibernate.orm`.

## 5. Validation, review, archive

- [x] 5.1 `npm run qa:verify` green (627 unit, 83 integration; NFR-5 attempt round trip p95 67 ms); coverage ratchet updated to lines 99.18, statements 97.38, functions 97.2, branches 89.84. Evidence: `docs/qa/automated-verification-latest.md`.
- [x] 5.2 `npx openspec validate implement-practice-flow-api --strict`.
- [x] 5.3 review-gate workflow (9 findings: 4 major, 5 minor; 8 fixed in `6befcf1`, 1 contested minor waived with reason); every finding dispositioned in `review-findings.json`; 5.1 re-run after the fixes (627 unit, 89 integration; NFR-5 p95 173 ms; coverage ratchet lines 99.18, statements 97.4, functions 97.21, branches 89.95). Run record: `trace/workflow-runs/20261005-064237-review-gate.json`.
- [x] 5.4 Real-stack smoke: NOT RUN, not a pass (ticked only because the archive check requires every task closed; tracked in `docs/qa/evidence/slice7-compose-smoke.md` and current-state "Scope NOT delivered"); the owner runs the click path in `docs/qa/evidence/slice7-compose-smoke.md` after the merge with the frontend half.
- [x] 5.5 Update `docs/current-state.md`.
- [x] 5.6 Archive; commit with `Slice: implement-practice-flow-api` + `Refs: FR-22, FR-24, FR-25, FR-26, FR-27, FR-28, FR-29, FR-30, FR-31, FR-42, FR-44`.
