## 1. Contract and schema

- [x] 1.1 Confirm the `contract/` pin (docs `0168eba`, API 0.5.1) has the operations this change implements (`getStats`, `listReports`, `reevaluateReport`, `dismissReport`; `Stats`, `WeekStats`, `Report`, `LoggedAttempt`, `ReevaluationResult`). No contract change.
- [x] 1.2 Flyway migration `V6__progress_stats.sql` (`attempt_reevaluation` append-only, `xp_award.report_id` and kind `REEVALUATION`, queue index).

## 2. Failing tests first (red)

- [x] 2.1 Unit (`*Test.kt`): `StatsRulesTest` (sessions: 20/35/exactly 30-minute gaps, distinct tasks, qualifying; Prague weeks incl. Sunday 23:40 → Monday, DST spring and autumn weeks, week list of N; hints and reveals per week; success metric incl. current week, short history, independence of N; first-try counts) and `ReevaluationRulesTest` (outcome order, primary and replay decisions) (FR-40, FR-41).
- [x] 2.2 Integration (`*IT.kt`, Testcontainers Postgres): `ProgressStatsIT` (stats over HTTP: week entries, defaults and boundaries, every invalid `weeks` value, first-try rate incl. replay and re-evaluation, no data) and `FlagQueueIT` (queue contents, order and filter, long raw input, re-evaluation awards incl. in-progress task, mission completion, rank-up, replay, never lowers, unchanged, unparseable, 409s, dismiss, report id handling, admin-only 401/403); `PracticeLogCaptureIT` extended with the four endpoints (NFR-3) (FR-40, FR-41).
- [x] 2.3 Run and confirm RED on assertions. Evidence: `docs/qa/evidence/slice9-red-run.md`.

## 3. Domain and application (green)

- [x] 3.1 `domain/`: `Stats.kt`, `Reevaluation.kt`; `AttemptRecord.reevaluation` / `effectiveCorrect`; `AwardKind.REEVALUATION`.
- [x] 3.2 `application/`: `ProgressStats`, `FlagQueue`, `Gamification.onReevaluation`; port additions; content public API `ContentCatalog`.

## 4. Adapters (green)

- [x] 4.1 `adapter/out/persistence`: `JdbcPracticeStore` (re-evaluations, reports), `JdbcXpLedger` (report id, task XP).
- [x] 4.2 `adapter/in/web`: `AdminStatsController` implements `AdminStatsApi`; `AdminStatsProblemAdvice`; catalog messages.

## 5. Validation, review, archive

- [x] 5.1 `npm run qa:verify` green (800 unit, 161 integration; NFR-5 attempt round trip p95 78 ms); coverage ratchet tightened only (lines 99.27, statements 97.76, functions 97.74, branches 90.94). Evidence: `docs/qa/automated-verification-latest.md` (2026-10-07, run after the review fixes).
- [x] 5.2 `npx openspec validate implement-progress-stats-api --strict`. Evidence: the pre-commit hooks of `9c24960` and `2dfbb77` (`openspec validate --all --strict`, 8 passed) and qa:verify.
- [x] 5.3 review-gate workflow ($8.81; 8 findings: 1 major, 7 minor; 6 fixed in `2dfbb77`, 2 minor waived with reasons); every finding dispositioned in `review-findings.json` (PD-15); 5.1 re-run after the fixes. Run record: `trace/workflow-runs/20261007-202408-review-gate.json`.
- [x] 5.4 Real-stack smoke: NOT RUN, not a pass (ticked only because the archive check requires every task closed; tracked in current-state "Scope NOT delivered"); run by the coordinator after the merge with the frontend half on the Compose stack (stats page, a report re-evaluated after a spec fix with XP visible on HQ, a dismiss); evidence `docs/qa/evidence/slice9-compose-smoke.md`.
- [x] 5.5 Update `docs/current-state.md`.
- [x] 5.6 Archive; commit with `Slice: implement-progress-stats-api` + `Refs: FR-40, FR-41`.
