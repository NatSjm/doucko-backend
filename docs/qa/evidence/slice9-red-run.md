# Slice 9 (implement-progress-stats-api) — RED run, task 2.3

- **Date:** 2026-10-07, Europe/Prague
- **Command:** `node scripts/mvnw.mjs -B verify -Dmaven.test.failure.ignore=true -Dspotless.check.skip=true -Djacoco.skip=true -Dtest=StatsRulesTest,ReevaluationRulesTest,PracticeRulesTest,ModularityTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ProgressStatsIT,FlagQueueIT,PracticeLogCaptureIT -Dfailsafe.failIfNoSpecifiedTests=false`
- **Against:** compile-only stubs marked `// RED stub`: `StatsRules.sessions` empty, `StatsRules.weeks` all-zero
  weeks, `StatsRules.metricMet` false, `StatsRules.firstTry` 0 of 0, `ReevaluationRules.decide` always `NoChange`,
  and `TaskProgress` still reading the original verdicts. The plumbing was in place: `V6__progress_stats.sql`,
  the `PracticeStore` / `XpLedger` additions and their Postgres adapters, `ContentCatalog`, `ProgressStats`,
  `FlagQueue`, `Gamification.onReevaluation`, `AdminStatsController` with its problem advice and mapping, the
  catalog messages.

Totals: unit 46 run, 25 failures, 1 error; integration 37 run, 17 failures, 1 error. No compile errors.

| Test class | Run | Failed | Why |
|---|---|---|---|
| StatsRulesTest | 24 | 20 + 1 error | assertions: sessions `expected:<1> but was:<0>`, `tasksPerSession` empty, hints and reveals 0, metric `expected:<true> but was:<false>`, first-try `FirstTryCount(0, 0)`. Passing: the `PragueWeeks` boundary checks (the week arithmetic is plumbing) and "less than 4 weeks is not met" (the stub answers false). The error was a test bug (a 5th week asked from a 4-week list), fixed before GREEN |
| ReevaluationRulesTest | 12 | 5 | assertions: `expected:<Improved(state=CORRECT_SECOND_TRY)> but was:<NoChange>` (and FIRST_TRY, ReplayCorrected); `TaskProgress` with a re-evaluated attempt `expected:<CORRECT_FIRST_TRY> but was:<UNRESOLVED>`. Passing: outcome order, the no-change cases, re-grading (plumbing over slice-7 `Grading`) |
| ProgressStatsIT | 15 | 9 + 1 error | assertions: week figures all 0, metric false, first-try `(0, 0)`. The error was a test bug (`atTime(16, 67)`), fixed before GREEN. Passing: week list and default, every invalid `weeks` value (400 naming `weeks`), 401 before validation, no data, topic listing |
| FlagQueueIT | 21 | 8 | assertions: `changed` false, `xpAwarded` 0 where 4 / 10 / 10+20 expected, `newTaskState` null, replay not recorded. Passing: queue contents, order and filter, raw input verbatim, excerpt, locked part, dismiss, 404/400/409/401/403, the no-change scenarios |
| PracticeLogCaptureIT | 1 | 0 | the new admin calls already log nothing (NFR-3) |
| PracticeRulesTest, ModularityTest | 10 | 0 | unchanged behaviour; `ContentCatalog` is a public content API |

Log: `../scratch/s9-backend-red.log` (machine-local, not committed).
