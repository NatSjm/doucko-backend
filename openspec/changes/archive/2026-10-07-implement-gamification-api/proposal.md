# Implement gamification (backend)

## Why

Slice 8 of the capability plan (`contract/docs/mvp-capability-plan.md` §4.8).
Store specs: `gamification` (`npx openspec show gamification --type spec --store doucko`)
and the "Mission complete screen" and "Replay completed missions" requirements
of `practice-flow`. Slice 7 left the XP and rank fields of the contract as
placeholders; this change makes them real: task XP with hint costs and a
minimum, the mission completion bonus with shared-task completion (OQ-15,
OQ-28), the rank ladder, one-shot rank-up celebrations (`ackRankUp`), HQ
progress, the mission summary and the gamification rules of replay. The game
only ever rewards: XP is an append-only ledger (FR-38). Backend-owned
requirements: FR-32, FR-33, FR-34, FR-35, FR-36, FR-37, FR-38, FR-39 (FR-34 and
FR-35 backend only; the others shared with the frontend).

## What Changes

- `cz.doucko.practice` gains the gamification rules: `XpRules` (award table,
  hint cost, minimum), `RankLadder`, the run summary arithmetic, and the
  `Gamification` use case behind `submitAttempt`, `getHq`,
  `getMissionBriefing`, `getMissionSummary` and `ackRankUp`. The slice-7
  placeholders (`xpAwarded`, `bonusXp`, `completedMissionIds`, `rankUp`,
  `totalXp`, `rank`, `nextRank`, `pendingRankUp`, `xpAvailable`, `xpEarned`,
  `rankReachedDuringRun`) are replaced; `PlaceholderLadder` is removed.
- XP values and the rank ladder come from one JSON configuration file
  (`classpath:gamification.json` by default, `doucko.gamification.config`
  points elsewhere), validated strictly when the backend starts: the backend
  refuses to start and names the offending key (OQ-29).
- `cz.doucko.content` gains `ServedContent.missionsContaining(taskId)` and publishes `PublishedMissionChanged` when a mission is
  published or a published mission's task list is edited; practice credits the
  completion bonus of a mission that is already complete then (OQ-28).
- Flyway `V5__gamification.sql`: `xp_award` (append-only ledger, a trigger
  refuses UPDATE and DELETE, one task award per task, one bonus per mission)
  and `rank_celebration` (append-only record of played celebrations).
- No contract change: every field already exists in API 0.5.1.

## Impact

- Store specs: gamification, practice-flow (summary, replay) · Contract pin: docs `0168eba` (API 0.5.1), no contract change
- Modules: `cz.doucko.practice` (extended); `cz.doucko.content` (public event `PublishedMissionChanged`, query `ServedContent.missionsContaining`) · Flyway: `V5__gamification.sql`
- Dependencies: slices 6 and 7; no new Maven dependency
- Frontend: relies on the behaviour in design.md "Key decisions" (when `rankUp` and `pendingRankUp` are set and cleared, summary and replay status codes, `xpAvailable`); summarized for the frontend half in `../scratch/slice8-backend-behaviour.md`.
