# Slice 6 (implement-authoring-missions-api) — RED run, task 2.4

- **Date:** 2026-10-04 (Europe/Prague)
- **Command:** `node scripts/mvnw.mjs -B test` (whole unit suite; the Docker-backed `MissionsIT` and the
  extended `ContentAuthoringIT` run in `mvnw verify` after the implementation)
- **Against:** compile-only stubs in `domain/Missions.kt`: `MissionRules.validate` passes every draft
  through unchecked (`Valid` with the submitted values), `MissionRules.blockers` returns no blockers, and
  `publish`, `topicChange`, `edit`, `taskStatus`, `taskEdit`, `taskDelete` and `missionDelete` return no
  refusal. The public API (`ContentUsage`, `ServedContent`), ports, `MissionService`,
  `ServedContentService`, the guard call sites in `TaskService`, `JdbcMissionStore`, `JdbcContentLock`,
  `MissionController`, the body and problem advices, migration `V3__missions.sql` and the message catalog
  were in place (plumbing; the rules under test were the stubs).

Totals: 604 run, 38 failures, 5 errors, all in the two new mission classes. Every pre-existing class
stayed green (admin, checking, profile, platform, the slice-5 content classes, `ModularityTest`,
`RequestContentNotLoggedTest`).

| Test class | Run | Failed | Why |
|---|---|---|---|
| MissionRulesTest | 14 | 13 (8 failures, 5 errors) | assertions: `Expected EmptyMission / LastTask / TopicLocked / TaskDraftInPublished / TaskGraphicInPublished / TaskDeleteInPublished / Used … but actual was null`, wrong blockers; 5 errors are `ClassCastException: Validation$Valid cannot be cast to Validation$Invalid` in the test's `errors()` helper, i.e. the stub accepted a draft the spec refuses (a wrong outcome, not a compile error) |
| MissionApiTest | 42 | 30 | assertions: `Status expected:<400> but was:<201>` (12: position, topic, task ids, 51 tasks), `expected:<409> but was:<200>` (9: publish, join, last task, topic, task draft / graphic / topic, deletes), `$.deletable expected:<false> but was:<true>` (5), `expected:<400> but was:<200>` (4) |

The 1 passing `MissionRulesTest` case and the 12 passing `MissionApiTest` cases check positive paths
the plumbing already satisfies (create keeps the order, sharing, reorder, a draft mission holding draft
and graphic tasks, publishing a valid mission and the served query, deleting unused content, 401/403,
404 and 400 for malformed ids and bodies, the slice-5 source guard). No compile errors.
