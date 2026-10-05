# Implement practice flow (backend)

## Why

Slice 7 of the capability plan (`contract/docs/mvp-capability-plan.md` §4.7).
Store specs: `practice-flow` (`npx openspec show practice-flow --type spec --store doucko`)
and the attempt-log part of `progress-stats` (requirements "Attempt log
contents" and "Attempt log is append-only"), with the endpoint rule of
`answer-checking` ("Unreadable submissions are refused at the submission
endpoint", FR-22), the translation setting of `child-profile` and the
admin-access scenario "Child write is limited to its own practice" (NFR-1,
deferred from slice 2). The child opens HQ without a login, picks or
continues a mission and solves its tasks: two attempts, then the reveal,
hints on demand, a problem report, and every attempt and hint saved at once.
Backend-owned requirements: FR-22, FR-24, FR-25, FR-26, FR-27, FR-28, FR-29,
FR-30, FR-31, FR-42, FR-44 and NFR-5 (with the frontend). The plan's DoD also
carries FR-12's child-facing scenarios over from slice 6 and the real
`ContentUsage` adapter (FR-56).

## What Changes

- New module `cz.doucko.practice` implements the generated `ChildHqApi`
  (`getHq`, `getMissionBriefing`, `getMissionSummary`, `ackRankUp`) and
  `ChildPracticeApi` (`getCurrentTask`, `getTask`, `submitAttempt`,
  `openNextHint`, `reportProblem`, `getChildImage`, `startReplay`). Every
  child request is served only through the slice-6 `ServedContent` query.
- Attempts are graded with the pure checker; a submission with any
  unreadable part is refused whole as `UNPARSEABLE` and costs nothing
  (FR-22). Feedback comes only through the `TutorPort` seam, whose one MVP
  implementation is deterministic (FR-44).
- Every graded attempt (raw and normalized input per part, verdicts, hints
  used, attempt number, timestamp, replay flag), every opened hint and every
  problem report is stored at once (FR-31, FR-42); the attempt log is
  append-only.
- `practice` implements the content port `ContentUsage` (attempts, problem
  reports, opened hints), so the slice-6 delete guards and
  `AdminTask.attemptCount` now see real usage (FR-56).
- `cz.doucko.content` public API grows: `ServedContent.task` (the served task
  with its parts, answer specs, hints, translations and images),
  `ServedContent.image`, and the topic name on `ServedMission`.
  `cz.doucko.profile` gets its first public API, `ChildProfile` (display name
  for HQ, the translation setting).
- XP, ranks, the mission summary and replays belong to slice 8. Their
  contract fields are placeholders here: `AttemptResult.xpAwarded` and
  `bonusXp` 0, `completedMissionIds` [], `rankUp` null; HQ `totalXp` 0 with the
  first rank of a fixed placeholder ladder; `MissionSummary` with `xpEarned` 0
  and no rank; `ackRankUp` 204 with nothing pending. Replay runs are stored
  (the attempt log must mark replay attempts) with the minimal contract
  behavior.
- Flyway `V4__practice.sql`: `practice_run`, `attempt`, `opened_hint`,
  `problem_report`.

## Impact

- Store specs: practice-flow, progress-stats (attempt log), answer-checking (FR-22), admin-access (NFR-1 scenario), content-authoring (FR-12 child-facing scenarios) · Contract pin: docs `0168eba` (API 0.5.1), no contract change
- Modules: `cz.doucko.practice` (new); `cz.doucko.content` and `cz.doucko.profile` (public API extended) · Flyway: `V4__practice.sql`
- Dependencies: slices 3, 4, 6; no new Maven dependency
- Frontend: relies on the status codes and problem shapes in design.md "Error handling" and on the state machine in "Key decisions".
