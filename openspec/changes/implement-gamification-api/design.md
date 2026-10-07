# Design — implement-gamification-api

## Goals / Non-goals

Goals: the backend side of gamification (FR-34..FR-39) and of the practice-flow
"Mission complete screen" (FR-32) and "Replay completed missions" (FR-33):
replace every slice-7 placeholder of the contract with the real rules.

### Store-spec split

| Store-spec requirement | Here |
|---|---|
| gamification: XP per task (FR-34, OQ-4, OQ-15) | yes: award table, hint cost, minimum, once per task, UNPARSEABLE neutral, hints after the resolution ignored |
| gamification: XP configuration is validated (FR-34, FR-35, OQ-29) | yes: strict JSON configuration, startup refusal naming the key |
| gamification: XP is granted only by the server (FR-34, FR-35) | yes: no write method on HQ (405, slice 7 rule re-tested with XP), client XP fields in an attempt ignored. The re-evaluation scenario needs slice 9's endpoint; its 401 is already the admin chain (re-tested here) |
| gamification: Mission completion bonus (FR-35, OQ-13, OQ-15, OQ-17, OQ-28) | yes, incl. shared-task completion and the publish-time bonus |
| gamification: Rank ladder (FR-36, OQ-1, OQ-29) | yes: the ladder lives in the same configuration file (the contract gives the backend the ladder) |
| gamification: Rank-up celebration (FR-36) | backend part: `AttemptResult.rankUp`, `HqView.pendingRankUp`, `MissionSummary.rankUp` / `rankReachedDuringRun`, `ackRankUp`. Animation and reduced motion are frontend |
| gamification: Progress on HQ (FR-37) | backend part: `totalXp`, `rank`, `nextRank` (null at the top), `solvedTasks`. The bar is frontend |
| gamification: No negative mechanics (FR-38) | backend part: append-only ledger, no deduction anywhere, `xpAwarded` never negative. Screens are frontend |
| gamification: Swappable skin (FR-39) | backend part: rank names and thresholds come from the configuration file; a second file changes names, not behaviour; the shipped rank names pass the IP deny-list; an incomplete ladder is refused. School, mentor, copy and colours are the frontend skin |
| gamification: Light and dark theme (FR-58) | frontend only |
| practice-flow: Mission complete screen (FR-32) | backend part: `getMissionSummary` (`xpEarned`, `firstTryCount`, rank-up) |
| practice-flow: Replay completed missions (FR-33) | yes: no XP, no bonus, summary 0 XP; run handling unchanged from slice 7 |
| practice-flow: Mission list on HQ "Shared task closes the second mission" | the XP part (30 XP) |

Non-goals: admin re-evaluation (slice 9; it will call the same award code and
can raise a pending rank-up), the frontend skin, the animation, the real-stack
smoke (run by the coordinator after the merge).

## Key decisions

1. **Gamification lives in `cz.doucko.practice`.** The awards are decided by
   the attempt use case under the practice lock and need the practice state of
   every task (primary scope). A separate module would need a cycle (practice →
   XP → practice state). Pure rules in `practice/domain` (`XpRules`,
   `RankLadder`, `RunXp`), the use case `Gamification` in
   `practice/application`, the ledger port `XpLedger`.
2. **Configuration is one JSON file** (`classpath:gamification.json`; the
   property `doucko.gamification.config` names another Spring resource, e.g.
   `file:/config/gamification.json`). JSON keeps the type of every value, which
   the spec needs: `10` is valid, `"10"`, `10.0`, `2.5`, `true`, `null` are not.
   Keys: `xp.firstAttempt`, `xp.secondAttempt`, `xp.revealed`, `xp.hintCost`,
   `xp.missionBonus` (each 0..1000), `xp.minimum` (1..1000), `ranks` (non-empty
   array of `{name, minXp}`; `name` 1..50 code points after trimming; `minXp`
   0..1 000 000, first 0, strictly increasing). Every key is required, unknown
   keys are refused (a typo never silently falls back). The file is read and
   validated when the context starts; an invalid file stops the start with an
   `IllegalStateException` whose message names the key (`xp.missionBonus`,
   `ranks[1] (rank 2).minXp`) and the rule, never the file content. Trade-off:
   not a Spring property (env vars are untyped strings, so `"10"` could not be
   told from `10`).
3. **Award rule.** On the first resolution of a task (the primary scope
   resolves a task once, slice 7 decision 1): `CORRECT_FIRST_TRY` →
   `max(minimum, firstAttempt − hints × hintCost)`, `CORRECT_SECOND_TRY` →
   `max(minimum, secondAttempt − hints × hintCost)`, `REVEALED` →
   `max(minimum, revealed)` (no hint cost: "a revealed task always earns exactly
   1 XP"). `hints` = hints opened in the primary scope before the resolving
   attempt (the attempt's stored `hints_used`). Replays, wrong attempts and
   UNPARSEABLE submissions award nothing.
4. **Mission completion.** After a first resolution of task T, every served
   mission containing T whose served tasks are all resolved in the primary
   scope and which has no bonus yet (found with the new content query
   `ServedContent.missionsContaining(taskId)`, which avoids loading every served
   task on each resolution) gets a `MISSION_BONUS` row, in the same
   transaction: `completedMissionIds` lists them, `bonusXp` sums them.
   `content` publishes `PublishedMissionChanged(missionId)` (public event of
   the content module, synchronous, inside the admin's transaction) after a
   mission is published and after a published mission's task list is saved;
   practice then credits the bonus of that mission if it is complete and
   unpaid (OQ-28; an edit that removes the last unresolved task completes the
   mission the same way). Republishing pays nothing: one bonus row per mission
   (unique index).
5. **Append-only ledger (FR-38).** `xp_award` rows are only inserted; a trigger
   refuses `UPDATE` and `DELETE`. The total is the sum. `seq` (bigserial)
   orders the ledger, so "total before an award" is the sum of the earlier rows.
   Every row carries the attempt that caused it (`attempt_id`, null for a
   publish-time bonus).
6. **Rank-up state.** `rank_celebration` is append-only; *celebrated* = the
   highest `rank_index` stored (0 when none). Rules:
   - `HqView.rank` = highest rank whose `minXp` ≤ total; `nextRank` the next one
     or null at the top; `pendingRankUp` = `rank` when `rank.index` >
     celebrated, else null.
   - `AttemptResult.rankUp`: when the attempt's awards (task XP plus every
     bonus it paid) move the rank up and `missionComplete` is false, it is the
     rank after the awards, and that index is stored as celebrated at once
     (the POST response is its only carrier). When `missionComplete` is true it
     is null and the crossing stays pending for `MissionSummary.rankUp` and
     `HqView.pendingRankUp`, whichever the client shows first. Always null in
     `TaskView.lastFeedback`, for UNPARSEABLE and in replays.
   - `ackRankUp(rankIndex)`: stores `min(rankIndex, current rank index)` when it
     is above celebrated; so the pending rank-up is cleared only when its index
     ≤ `rankIndex`, and an index above the current rank cannot pre-empt a later
     celebration. 204 always (also when nothing was pending); 400 for a bad
     body (slice 7).
7. **Summary of a run (FR-32).** The run is the latest completed run (slice 7:
   latest completed replay run, else the primary scope when the mission is
   complete; 409 when none, 404 when the mission is not served). Run awards =
   ledger rows whose attempt is an attempt *of this run through this mission*
   (`attempt.scope` = run, `attempt.mission_id` = mission). `xpEarned` = their
   sum (task XP, this mission's bonus, bonuses of other missions completed by
   those attempts); a replay has none, so 0. The *completing attempt* is the
   latest resolving attempt of the mission's tasks in the run's scope.
   `rankUp` = the rank after the completing attempt's awards when they moved the
   rank up and that rank is above celebrated, else null.
   `rankReachedDuringRun` = the highest rank reached by the run's awards (rank
   after the last run award vs. before the first) when it is ≤ celebrated and
   differs from `rankUp`; when `rankUp` is set, the rank reached before the
   completing attempt's awards, if the run crossed one earlier.
   `firstTryCount` unchanged from slice 7.
8. **Briefing `xpAvailable`** = (served tasks of the mission not resolved in
   the primary scope) × `xp.firstAttempt` + `xp.missionBonus` when the mission
   has no bonus row yet. 0 for a complete mission and during a replay.
9. **`TaskView.lastFeedback` keeps its XP.** `xpAwarded`, `bonusXp` and
   `completedMissionIds` of the latest attempt are re-read from the ledger rows
   of that attempt, so a reload shows what the POST showed; `rankUp` is null.
10. **HQ write methods.** `PUT`/`POST`/`PATCH /api/child/hq` stay 405 (slice 7's
    undeclared-method rule); attempt bodies bind only `answers` (slice 7). Both
    re-tested with XP present.

## Module + hexagon placement

- `practice/domain`: `XpConfig`, `XpRules`, `RankLadder`, `Rank` (moved from the
  placeholder), `XpAward`, `AwardKind`, `RunXp` (summary arithmetic).
  `PlaceholderLadder` is removed.
- `practice/application`: port `XpLedger`; `Gamification` (award on
  resolution, publish-time bonus, HQ progress, ack, summary, available XP);
  `GamificationSettings` (loads and validates the file; the parser is
  `GamificationConfigParser`, Jackson tree, no Spring); `Gamification` also listens to `PublishedMissionChanged`.
  `PracticeService` calls `Gamification`.
- `practice/adapter/out/persistence`: `JdbcXpLedger`.
- `practice/adapter/in/web`: mapping of the real values; `ackRankUp` calls the
  use case.
- `content` (public API): `PublishedMissionChanged`, published by `MissionService`;
  `ServedContent.missionsContaining(taskId)`.
- `src/main/resources/gamification.json`: the default values (10, 5, 1, 2, 1,
  20) and the placeholder ladder (0, 150, 400, 800, 1500) with placeholder
  hero-academy names.

## Data model (`V5__gamification.sql`)

- `xp_award(seq bigserial pk, id uuid unique, kind text check in ('TASK','MISSION_BONUS'), task_id uuid null fk task on delete restrict, mission_id uuid not null, attempt_id uuid null fk attempt on delete restrict, amount int check 0..1000, created_at timestamptz)`;
  checks: a `TASK` row has `task_id` and `attempt_id`, a bonus row has no
  `task_id`. Unique partial indexes: `(task_id) where kind = 'TASK'`,
  `(mission_id) where kind = 'MISSION_BONUS'`; index `(attempt_id)`.
  `mission_id` has no foreign key (like the attempt log). Trigger
  `xp_award_append_only` refuses `UPDATE` and `DELETE`.
- `rank_celebration(id bigserial pk, rank_index int check >= 0, celebrated_at timestamptz)`.

## Error handling

No new error cases. Unchanged from slice 7: summary 404 (mission not served) /
409 (no completed run), replay 404 / 409 (mission not complete), ackRankUp 400
(`rankIndex` missing, not an int32, negative) and 413. The only new failure is
the startup refusal of an invalid configuration (decision 2). A unique-index
violation on the ledger cannot happen under the practice lock; if it ever did,
the transaction rolls back (no partial award).

## Risks & mitigations

- An `AttemptResult.rankUp` whose response is lost is never replayed (the
  contract makes the POST its only carrier). Accepted by the contract.
- Ledger growth: one row per task and per mission; totals are summed in memory
  per request (one child, hundreds of rows).
- The trigger also blocks test cleanup by `DELETE`; tests use `TRUNCATE`.
