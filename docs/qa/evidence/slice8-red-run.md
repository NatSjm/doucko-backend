# Slice 8 (implement-gamification-api) — RED run, task 2.3

- **Date:** 2026-10-05 (unit layer, full battery) and 2026-10-07 (integration layer, targeted), Europe/Prague
- **Commands:**
  1. `node scripts/mvnw.mjs -B verify -Dmaven.test.failure.ignore=true -Dspotless.check.skip=true`
     — the whole unit suite ran; the integration phase was cut off inside `BackupRestoreIT` when the laptop
     went to sleep (the shell hit its time limit; the leftover `doucko-it-*` compose project was removed with
     `docker compose -p doucko-it-005e9d7c down -v`). The integration classes that finished before the cut
     (`AdminSessionIT`, `ContentAuthoringIT`, `MissionsIT`, `UploadLimitsIT`, `DouckoBackendApplicationIT`)
     were green.
  2. `node scripts/mvnw.mjs -B verify -Dmaven.test.failure.ignore=true -Dspotless.check.skip=true -Djacoco.skip=true -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=GamificationIT,GamificationConfigIT,PracticeIT -Dfailsafe.failIfNoSpecifiedTests=false`
     — the slice's integration classes and the slice-7 `PracticeIT` whose placeholder assertions this slice replaces.
- **Against:** compile-only stubs marked `// RED stub`: `XpRules.taskAward` always 0, `RankLadder.crossing`
  always null, `RunXp.summarize` always 0 XP and no ranks, `GamificationConfigParser.parse` lenient (no type,
  range, key or ladder checks; thresholds `index × 150`), and in `Gamification` the publish-time bonus,
  `ackRankUp`, `xpAvailable` and the mission-completion check (`complete` always false) did nothing. The
  plumbing was in place: `V5__gamification.sql`, `JdbcXpLedger`, `XpLedger` port, `Gamification` wiring in
  `PracticeService`, `PublishedMissionChanged` from `MissionService`, `GamificationSettings`, the mapping of
  every contract field, `gamification.json`.

Totals: unit 747 run, 111 failures, 1 error — all in the two new classes; every pre-existing unit class
stayed green (incl. `PracticeRulesTest`, `PracticeApiTest`, `ModularityTest`, `FixtureProvenanceTest`).
Integration (targeted) 65 run, 27 failures, 2 errors. No compile errors.

| Test class | Run | Failed | Why |
|---|---|---|---|
| GamificationConfigTest | 99 | 96 | assertions: `InvalidGamificationConfig` expected but nothing thrown for every invalid value, missing key, unknown key, ladder order, threshold value and incomplete ladder; startup expected to fail but started; the shipped ladder read as `0, 150, 300, 450, 600`. Passing: the two deny-list checks (the shipped names were already clean) and "a valid file starts" (the lenient stub reads it) |
| GamificationRulesTest | 21 | 15 + 1 error | assertions: award table `expected:<10> but was:<0>` (and every row), configured values, crossings `null`, summaries `expected:<101> but was:<0>`; the error is a `!!` on the stubbed null rank-up (`one completion crossing two thresholds`). Passing: rank from total XP, next rank / XP bar |
| GamificationIT | 27 | 21 + 2 errors | assertions: awards `[0, 0, 0, 0, 0, 0, 0, 0]`, bonus `expected:<20> but was:<0>`, totals, publish-time bonus `expected:<160> but was:<140>`, `completedMissionIds` empty, `xpAvailable 0`, summary XP 0, ack `expected:<2> but was:<3>` (wrong ladder), append-only check `expected:<10> but was:<0>`; the 2 errors are JSON paths on the stubbed null `rankUp`. Passing (plumbing / slice 7 behaviour): no write operation for progress, re-evaluation 401, summary 404/409, solved tasks counted once |
| GamificationConfigIT | 3 | 3 | assertions: `expected:<12> but was:<0>`, total `expected:<182> but was:<140>`, rank name `Ochránce města` vs `Pomocník` (lenient thresholds) |
| PracticeIT | 35 | 3 | the three slice-7 placeholder assertions replaced by the spec values: briefing `xpAvailable` 40, 2nd-try award 5 + bonus 20, summary `xpEarned` 31 |

Logs: `../scratch/s8-backend-red.log`, `../scratch/s8-backend-red-it.log` (machine-local, not committed).
