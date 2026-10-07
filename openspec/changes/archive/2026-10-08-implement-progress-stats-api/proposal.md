# Implement progress stats (backend)

## Why

Slice 9 of the capability plan (`contract/docs/mvp-capability-plan.md` §4.9), the
last MVP slice. Store spec: `progress-stats`
(`npx openspec show progress-stats --type spec --store doucko`), with the
re-evaluation rules it shares with `gamification` and `practice-flow`. The
admin needs to see whether practice happens (weekly sessions, qualifying
sessions, hints, reveals, first-try rate per topic and the success metric) and
to trust the checker: a flag queue of the child's problem reports where an
attempt checked against a wrong answer key can be re-evaluated (only ever
adding XP) or the report dismissed. Backend-owned requirements: FR-40, FR-41
(shared with the frontend). The attempt log itself (FR-42) was delivered by
slice 7; this change adds the re-evaluated verdict next to it.

## What Changes

- `AdminStatsApi` implemented in `cz.doucko.practice`: `getStats`
  (`GET /api/admin/stats?weeks=`), `listReports` (`GET /api/admin/reports?status=`),
  `reevaluateReport` and `dismissReport`
  (`POST /api/admin/reports/{reportId}/reevaluate|dismiss`). Admin-only through the
  existing `/api/admin/**` chain (session, CSRF on POST, 401 before 400).
- Pure rules in `practice/domain`: sessions (30-minute gap), Europe/Prague
  weeks, the success metric, first-try counts, and the re-evaluation decision
  (outcome order, replay rule).
- Re-evaluation re-checks the attached attempt with the task's current answer
  spec, records the new verdicts next to the original (append-only), changes
  the task's first-resolution outcome when it improves it, awards the XP
  difference and any completion bonus through the gamification ledger, and can
  raise `HqView.pendingRankUp`. Practice state now reads re-evaluated verdicts
  of primary-scope attempts.
- `cz.doucko.content` gains a public query `ContentCatalog`: topics with
  published tasks, and a task's prompt and current parts whatever its status.
- Flyway `V6__progress_stats.sql`: `attempt_reevaluation` (append-only, a
  trigger refuses UPDATE and DELETE), the ledger kind `REEVALUATION` with
  `xp_award.report_id`, and the flag-queue index.
- No contract change: every operation and field exists in API 0.5.1.

## Impact

- Store specs: progress-stats (gamification and practice-flow for the re-evaluation awards) · Contract pin: docs `0168eba` (API 0.5.1), no contract change
- Modules: `cz.doucko.practice` (extended); `cz.doucko.content` (public query `ContentCatalog`) · Flyway: `V6__progress_stats.sql`
- Dependencies: slices 7 and 8; no new Maven dependency
- Frontend: relies on the behaviour in design.md "Key decisions" (week and session arithmetic, queue order, re-evaluation results and status codes); summarized for the frontend half in `../scratch/slice9-backend-behaviour.md`.
