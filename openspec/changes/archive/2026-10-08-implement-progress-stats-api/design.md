# Design — implement-progress-stats-api

## Goals / Non-goals

Goals: the backend side of progress-stats (FR-40, FR-41): the stats API, the
flag queue, re-evaluation and dismiss, over the attempt log, opened hints and
problem reports stored by slice 7 and the XP ledger of slice 8.

### Store-spec split

| Store-spec requirement | Here |
|---|---|
| Attempt log contents (FR-42) | already stored by slice 7; exposed here as `Report.attempt` (`LoggedAttempt`) |
| Attempt log is append-only (FR-42, OQ-20) | yes: a re-evaluation is a new `attempt_reevaluation` row next to the attempt (trigger refuses UPDATE/DELETE); editing a spec changes nothing stored |
| Session definition (FR-40, OQ-14) | yes (decision 2) |
| Weekly hint and reveal figures (FR-40) | yes (decision 3) |
| Practice statistics page (FR-40) | data: yes (decisions 2–5). Empty-state copy and "—" rendering are frontend |
| Flag queue (FR-41) | data, order, filter: yes (decision 6). "Offers dismiss but not re-evaluate", wrapping, opening the task are frontend |
| Re-evaluation of a reported attempt (FR-41, OQ-20, OQ-17, OQ-4) | yes (decisions 7–9) |
| Dismissing a report (FR-41) | yes (decision 10) |
| Report id handling (FR-41) | yes (decision 11) |
| Stats and flag queue are admin-only | yes: the existing `/api/admin/**` chain; re-tested per endpoint. "Child UI has no stats" is frontend |

Non-goals: the admin screens, export, any bulk re-evaluation, the real-stack
smoke (run by the coordinator after the merge).

## Key decisions

1. **Everything lives in `cz.doucko.practice`.** The attempt log, the
   practice lock, the task-state derivation and the XP ledger are there;
   re-evaluation must change all of them in one transaction under the lock. A
   separate `progress` module would need practice to expose its log and its
   award code, and practice would need the re-evaluated verdicts back (a
   cycle). Pure rules in `practice/domain/Stats.kt` and
   `practice/domain/Reevaluation.kt`; use cases `ProgressStats` and `FlagQueue`
   in `practice/application`; the admin controller in `practice/adapter/in/web`.
   No new Modulith module, so `ModularityTest` is unchanged (it still verifies
   the content dependency below).
2. **Sessions.** Every logged attempt of every scope (primary and replay runs,
   OQ-17) sorted by `created_at` (ties by attempt id) is split where two
   consecutive attempts are more than 30 minutes apart; exactly 30 minutes
   keeps the session (OQ-14). Answered tasks = distinct task ids in the
   session; qualifying = at least 10. A session belongs to the week of its
   first attempt. UNPARSEABLE submissions are never logged, so never counted.
3. **Weeks.** A week runs from Monday 00:00 to the next Monday 00:00 in
   Europe/Prague (`LocalDate.with(MONDAY)` on the Prague local date of an
   instant), so DST weeks are 167 or 169 hours long and a week is identified
   by its Monday (`weekStart`). The current week is the week of "now"; the
   response holds exactly `weeks` entries, oldest first, the current week
   last. `hintsUsed` = opened-hint rows (every scope) by the Prague week of
   `opened_at` (a hint with no later attempt counts). `revealed` = logged
   attempts with attempt number 2 whose *original* verdict is wrong (every
   scope; the attempt that caused the reveal), by its week; a later
   re-evaluation never removes it.
4. **Success metric.** `metricMet` = each of the 4 weeks before the current
   week has at least 3 qualifying sessions, independent of `weeks`; weeks with
   no data count as 0, so too little history is `false`.
5. **First-try rate.** One entry per topic that has at least one `PUBLISHED`
   `TEXT` task, in the content topic order. `resolved` = the topic's `TEXT`
   tasks (any status) whose primary-scope outcome (after re-evaluation) is
   resolved; `firstTry` = those whose outcome is `CORRECT_FIRST_TRY`. Replays
   never change it. A topic with `resolved` 0 is returned with `firstTry` 0
   (the UI shows "—").
6. **Flag queue.** Every report (no paging; one child), `OPEN` first, then
   `FIXED` and `DISMISSED` together; each group oldest first (`created_at`,
   then id). `status` filters exactly (pattern-checked by the generated
   interface). `taskPromptExcerpt` = the first 80 code points of the task's
   current Czech prompt, with `…` appended when cut (nothing is trimmed, review F04). Prompts and
   attempts of the listed reports are read in one batch query each (`ContentCatalog.prompts`,
   `PracticeStore.attemptsById`; review F02). `attempt` (null when none
   was attached): `rawInput` holds every part typed in that attempt verbatim (a
   part locked as correct on attempt 1 is not re-entered on attempt 2 and is
   left out), `normalized` and `verdicts` hold every part, `replay`,
   `hintsUsed`, `attemptNo`, `createdAt`, and `reevaluation` (`verdicts`,
   `reevaluatedAt`) or null.
7. **Re-checking.** Under the practice lock: unknown report → 404; status
   not `OPEN` → 409; no attached attempt → 409. The attempt is graded with the
   task's *current* parts (read through `ContentCatalog`, whatever the task's
   status) by the slice-7 `Grading.grade`, with the attempt's raw inputs and
   its locked parts (locked parts keep `CORRECT`). If the stored input no
   longer fits a part's shape (e.g. a part changed between choice and text),
   or any part is unreadable under the new spec, nothing changes.
8. **When a re-evaluation changes something.** Outcomes are ordered
   unresolved < revealed < correct on the 2nd attempt < correct on the 1st
   attempt.
   - *Primary-scope attempt:* the task's outcome is recomputed with the new
     verdict in place of the attempt's (and any earlier re-evaluations). Only
     if it is better than the current outcome: an `attempt_reevaluation` row
     stores the new per-part verdicts with its own timestamp, the report
     becomes `FIXED`, and `newTaskState` is the new outcome.
   - *Replay attempt:* the first-resolution outcome, the first-try rate,
     mission completion and XP never change. "Better" means the attempt is
     now correct as a whole (one of two wrong parts fixed is not better;
     review F06). If the attempt was wrong and is now correct, the new
     verdicts are recorded, the report becomes `FIXED`
     with `xpAwarded` 0, `bonusXp` 0, `newTaskState` null.
   - Otherwise `changed: false`, `xpAwarded` 0, `bonusXp` 0,
     `completedMissionIds` [], `newTaskState` null, the report stays `OPEN`
     and nothing is stored (200, not an error).
   Practice state everywhere (HQ, briefing, task screen, attempts, missions,
   summary, the first-try rate) reads a primary-scope attempt's re-evaluated
   verdict as its correctness (`AttemptRecord.effectiveCorrect`), so a task
   resolved by a re-evaluation is no longer asked, and its mission can become
   complete. Replay attempts keep their original verdict for their run. The
   task screen's `lastFeedback` shows the attempt that decides the state
   (attempt 1 once it counts as correct, else the latest) with its
   re-evaluated per-part verdicts, so state and verdicts agree (review F01);
   its XP stays what that attempt itself earned.
9. **XP of a re-evaluation (gamification, FR-38).** New award =
   `XpRules.taskAward(newOutcome, hints used at the attached attempt)`;
   `xpAwarded` = max(0, new award − the task XP already in the ledger for that
   task (its `TASK` row plus earlier re-evaluation rows)). It is one new
   ledger row of kind `REEVALUATION` (`task_id`, `report_id`, no `attempt_id`, so the
   child's attempt feedback and mission summaries never show it); a task with
   such a row counts as awarded (the startup reconciliation never pays it
   twice). Then every served mission containing the task that is now complete
   and unpaid gets its `MISSION_BONUS` row (no `attempt_id`, `report_id` set):
   `bonusXp`, `completedMissionIds`. Nothing marks a rank as celebrated, so a
   crossing shows as `HqView.pendingRankUp` the next time the child opens HQ
   (no mission-complete screen). One `REEVALUATION` row per report (unique).
10. **Dismiss.** `OPEN` → `DISMISSED` (200 with the report); `FIXED` or
    `DISMISSED` → 409, unchanged; unknown → 404. Nothing else changes. The
    status is the only mutable column of `problem_report` (updated with
    `WHERE status = 'OPEN'` under the lock).
11. **Report id.** The generated interface binds `reportId` as a UUID: a
    value that does not convert (`123`, `abc`, 5,000 characters) is 400 with
    `errors[].field = reportId` before any lookup; a UUID that matches no
    report is 404. Same converter as every other id path of the API.
12. **Query parameters.** `weeks` and `status` are pattern-checked strings in
    the generated interface; any violation (empty, out of range, oversized,
    non-ASCII digits, wrong case, repeated parameter) is 400 with
    `errors[].field` = `weeks` / `status`; absent `weeks` is 4, absent
    `status` is no filter. Authorization runs first (401 without a session,
    whatever the parameters).

## Module + hexagon placement

- `practice/domain`: `Stats.kt` (`PragueWeeks`, `Sessions`, `WeeklyStats`,
  `SuccessMetric`, `FirstTry`), `Reevaluation.kt` (`OutcomeOrder`,
  `ReevaluationRules`), `Model.kt` (`AttemptRecord.reevaluation`,
  `effectiveCorrect`, `ReportStatus`, `StoredReport`), `Gamification.kt`
  (`AwardKind.REEVALUATION`, `XpAward.reportId`).
- `practice/application`: `ProgressStats` (stats use case), `FlagQueue`
  (list, re-evaluate, dismiss), `Gamification.onReevaluation`; ports
  `PracticeStore` (+ `allAttempts`, `allHints`, `reports`, `report`,
  `attempt`, `insertReevaluation`, `setReportStatus`) and `XpLedger`
  (+ `taskXp`).
- `practice/adapter/out/persistence`: `JdbcPracticeStore` (attempts read with
  their re-evaluation), `JdbcXpLedger`.
- `practice/adapter/in/web`: `AdminStatsController` implements `AdminStatsApi`;
  `AdminStatsProblemAdvice`; mapping in `StatsMapping`.
- `content` (public API): `ContentCatalog` (`topics()`, `task(taskId)`),
  implemented by `ContentCatalogService`.

## Data model (`V6__progress_stats.sql`)

- `attempt_reevaluation(attempt_id uuid pk fk attempt on delete restrict,
  report_id uuid not null fk problem_report on delete restrict, verdicts jsonb
  not null (object part key → CORRECT|WRONG), correct boolean not null,
  reevaluated_at timestamptz not null)`; trigger refuses UPDATE and DELETE. One
  row per attempt: a recorded re-evaluation is always correct, so a later one
  can never improve it.
- `xp_award`: new column `report_id uuid null fk problem_report`; kind check
  gains `REEVALUATION`; shape check: `TASK` (task + attempt), `MISSION_BONUS`
  (no task), `REEVALUATION` (task + report, no attempt); unique partial index
  `(report_id) where kind = 'REEVALUATION'`. The append-only trigger stays.
- `problem_report_queue_idx (status, created_at)`.
- Tests clean with `TRUNCATE` (the triggers refuse DELETE).

## Error handling

All problem+json from the catalog (NFR-9), never quoting a submitted value
(NFR-3): 400 `errors[].field` = `weeks` / `status` / `reportId`; 401 / 403
from the admin chain; 404 unknown report; 409 report not `OPEN` (detail
`practice.conflict.report-not-open`) or no attempt attached
(`practice.conflict.report-no-attempt`). A re-evaluation that changes nothing
is a 200 with `changed: false`, never an error. A concurrent unique-index
violation cannot happen under the lock; if it ever did, the transaction rolls
back.

## Risks & mitigations

- The stats read the whole attempt log and hint table per request (one child,
  thousands of rows at most). Accepted; an index on `attempt (created_at)` is
  not needed at this size.
- A task's parts may change between the attempt and the re-evaluation;
  handled as "no change" whenever the stored input cannot be graded.
- Reading re-evaluated verdicts in practice state changes slice-7/8 code
  paths; covered by the existing practice and gamification suites plus the
  new re-evaluation tests.
