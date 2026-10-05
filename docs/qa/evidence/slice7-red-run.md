# Slice 7 (implement-practice-flow-api) — RED run, task 2.3

- **Date:** 2026-10-05 (Europe/Prague)
- **Command:** `node scripts/mvnw.mjs -B verify -Dmaven.test.failure.ignore=true -Dspotless.check.skip=true`
  (unit suite and the Docker-backed integration suite in one run)
- **Against:** compile-only stubs in `practice/domain/Rules.kt`: `TaskProgress.state` is always
  `UNRESOLVED` with 2 attempts left and no locked part, `Grading.shapeErrors` returns nothing,
  `Grading.grade` grades every part and never refuses a submission as unreadable, `MissionProgress.of`
  reports every mission `NEW` with 0 resolved, `ReportRules.validate` accepts every report. The plumbing was
  in place: content public API (`ServedContent.task`, `image`, `ServedMission.topicName`), profile public
  API `ChildProfile`, ports, `PracticeService`, `PracticeContentUsage`, `DeterministicTutor`,
  `JdbcPracticeStore`, `V4__practice.sql`, controllers, body and problem advices, messages, and the
  `org.springframework.jdbc` log guard.

Totals: unit 621 run, 8 failures; integration 83 run, 14 failures, 2 errors. Every pre-existing class
stayed green (admin, checking, profile, platform, content incl. `MissionsIT` and `ContentAuthoringIT`,
`ModularityTest`, `RequestContentNotLoggedTest` with the new practice requests).

| Test class | Run | Failed | Why |
|---|---|---|---|
| PracticeRulesTest | 10 | 7 | assertions: `expected:<CORRECT_FIRST_TRY> but was:<UNRESOLVED>`, `Collection should contain exactly: ["a"] but was: []` (locks), `Graded(...) is of type Grade.Graded but expected Grade.Unreadable` (2: FR-22 refusal, missing part), shape errors `but was: []`, `expected:<IN_PROGRESS> but was:<NEW>`, report errors `but was: []` |
| TutorTest | 5 | 1 | assertion: the architecture check listed `Feedback` itself (its data-class `copy`) next to `DeterministicTutor` as a constructor caller — a test defect, fixed by excluding the class's own `copy`; the rule under test was right |
| PracticeIT | 29 | 14 | assertions: `CORRECT_FIRST_TRY but was UNRESOLVED`, `UNPARSEABLE but was GRADED` (2), resolved counts `[0, 0]`, `IN_PROGRESS but was NEW`, continue target `null`, report `400 but was 201`, replay `200 but was 409` (mission never complete), resume and attempt counts |
| PracticeLogCaptureIT | 1 | 1 error | test setup: the self-made source PDF had 5 pages while the task body references page 57, so task creation was refused (`PathNotFound $['taskId']`); fixed to a 120-page PDF |
| PracticeTimingIT | 1 | 1 error | same setup defect as the log-capture test; fixed the same way |

The 15 passing `PracticeIT` cases check paths the plumbing already satisfies: the HQ shape and placeholder
rank, empty HQ, grouping and order, every published mission open, the FR-12 404 cases (draft mission,
task outside a published mission, graphic task, task of another mission, malformed ids), the task view,
NFR-1 child write, hints in order and the 409 when none is left, hint feedback, translation, the attempt
log row and the usage guard. `TutorSwapTest` passed (the seam itself is plumbing). No compile errors.
