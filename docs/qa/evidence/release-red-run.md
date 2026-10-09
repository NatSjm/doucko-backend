# Release review (release-review-api) — red, green (2026-10-09)

Finding F11 (major, practice) and F06 (minor, content) from the Phase 7 review gate
(`openspec/changes/release-review-api/review-findings.json`).

- **Command:** `node scripts/mvnw.mjs -B verify -Dmaven.test.failure.ignore=true -Dspotless.check.skip=true -Djacoco.skip=true -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=PracticeIT,ContentAuthoringIT -Dfailsafe.failIfNoSpecifiedTests=false`
- **RED** at `f7a7cfd` (tests only):
  - `PracticeIT` "a NUL character in an answer or a report note is a 400 problem, never a 500, and stores nothing": `Response status expected:<400> but was:<200>`
  - `ContentAuthoringIT` "a NUL character in task text is a 400 problem, not a conflict or a 500, and stores nothing": `Status expected:<400> but was:<409>`
- **GREEN** at `aedd85f`: `-Dit.test=PracticeIT,ContentAuthoringIT,FlagQueueIT,MissionsIT` plus all 813 unit tests — 75 IT, 0 failures, BUILD SUCCESS.
