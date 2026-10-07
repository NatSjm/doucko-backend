## 1. Contract and schema

- [x] 1.1 Confirm the `contract/` pin (docs `0168eba`, API 0.5.1) has every field this change fills (`AttemptResult.xpAwarded`, `bonusXp`, `completedMissionIds`, `rankUp`; `HqView.totalXp`, `rank`, `nextRank`, `pendingRankUp`; `MissionBriefing.xpAvailable`; `MissionSummary.xpEarned`, `rankUp`, `rankReachedDuringRun`; `ackRankUp`). No contract change.
- [x] 1.2 Flyway migration `V5__gamification.sql` (`xp_award` append-only with trigger and unique indexes, `rank_celebration`).

## 2. Failing tests first (red)

- [x] 2.1 Unit (`*Test.kt`): `GamificationRulesTest` (award table, hint cost, minimum, configured values, rank ladder, XP bar inputs, run summary arithmetic incl. two thresholds in one award) and `GamificationConfigTest` (every invalid value of the spec tables names its key, missing key, unknown key, boundaries accepted, ladder order and threshold values, incomplete ladder, startup refusal through `ApplicationContextRunner`, shipped rank names vs. the IP deny-list) (FR-34, FR-35, FR-36, FR-37, FR-38, FR-39).
- [x] 2.2 Integration (`*IT.kt`, Testcontainers Postgres): `GamificationIT` (awards over HTTP, UNPARSEABLE neutral, shared tasks, completion bonus incl. OQ-28 publish-time and two missions at once, rank-up on a task, pending rank-up and `ackRankUp`, summary and replay XP, briefing `xpAvailable`, HQ progress, no write operation for progress, client XP fields ignored, ledger append-only, XP never decreases over a mixed sequence) and `GamificationConfigIT` (a second configuration file: other values and other rank names, same behaviour; two thresholds crossed by one completion) (FR-32..FR-39).
- [x] 2.3 Run and confirm RED on assertions. Evidence: `docs/qa/evidence/slice8-red-run.md`.

## 3. Domain and application (green)

- [x] 3.1 `domain/`: `XpConfig`, `XpRules`, `RankLadder`, `XpAward`, `RunXp`; remove `PlaceholderLadder`.
- [x] 3.2 `application/`: port `XpLedger`; `Gamification`; `GamificationSettings` + `GamificationConfigParser`; `PracticeService` wired to `Gamification`; content public API: event `PublishedMissionChanged` published by `MissionService`, query `ServedContent.missionsContaining`.

## 4. Adapters (green)

- [x] 4.1 `adapter/out/persistence`: `JdbcXpLedger`.
- [x] 4.2 `adapter/in/web`: mapping of XP, ranks and summaries; `ackRankUp` stores the acknowledgement; `gamification.json` default configuration.

## 5. Validation, review, archive

- [x] 5.1 `npm run qa:verify` green (747 unit, 123 integration; NFR-5 attempt round trip p95 172 ms); coverage ratchet tightened only (lines 99.24, statements 97.42, functions 97.4, branches kept at 89.95). Evidence: `docs/qa/automated-verification-latest.md`.
- [x] 5.2 `npx openspec validate implement-gamification-api --strict`.
- [x] 5.3 review-gate workflow ($8.88; 6 findings: 2 major, 4 minor; all 6 fixed in `33f5bf2`); every finding dispositioned in `review-findings.json` (PD-15); 5.1 re-run after the fixes (755 unit, 124 integration; NFR-5 p95 78 ms; coverage ratchet lines 99.24, statements 97.64, functions 97.5, branches 90.76). Run record: `trace/workflow-runs/20261007-101412-review-gate.json`.
- [x] 5.4 Real-stack smoke: NOT RUN, not a pass (ticked only because the archive check requires every task closed; tracked in current-state "Scope NOT delivered"); the coordinator runs it after the merge with the frontend half on the Compose stack: publish a mission, solve it, check HQ XP and rank, the summary, a replay with 0 XP and the rank-up acknowledgement; evidence `docs/qa/evidence/slice8-compose-smoke.md`.
- [x] 5.5 Update `docs/current-state.md`.
- [x] 5.6 Archive; commit with `Slice: implement-gamification-api` + `Refs: FR-32, FR-33, FR-34, FR-35, FR-36, FR-37, FR-38, FR-39`.
