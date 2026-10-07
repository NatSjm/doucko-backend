# Slice 8 (implement-gamification-api + implement-gamification-ui) — real-stack smoke, task 5.4 / 4.4

- **Status:** PASS after the merge (2026-10-07), see below.

## Click path (API, through 127.0.0.1:8081)

Run `npm run backup` first: the stack holds the owner's data. Migration `V5__gamification.sql` is applied on
start. Use self-authored content only (NFR-4); clean up afterwards. Every child request carries no cookie.

1. `npm run stack:up`; sign in as the admin; upload a self-generated blank PDF.
2. Tasks T1 (INTEGER `12`, 2 hints), T2, T3 (INTEGER `12`), published; mission M = [T1, T2, T3] published.
3. HQ: rank 0, `nextRank` set, `pendingRankUp` null. Briefing M: `xpAvailable` 50 (3 × 10 + 20).
4. T1: open 1 hint, `12` → `xpAwarded` 8. T2: `5,,2` (UNPARSEABLE, 0), `11` (0), `12` → 5.
   T3: `1`, `2` → REVEALED, `xpAwarded` 1, `bonusXp` 20, `completedMissionIds` [M], `missionComplete`.
5. HQ: +34 XP, +3 solved. Summary M: `xpEarned` 34, `firstTryCount` 1, `taskCount` 3, `replay` false.
   Briefing M: `xpAvailable` 0.
6. Admin publishes mission C = [T1, T2] (all resolved): +20 XP (OQ-28); unpublish + republish: +0.
7. Mission R of enough new tasks to cross the next threshold at its completion: the completing attempt's
   `rankUp` is null; summary R `rankUp` and HQ `pendingRankUp` name the new rank; `ackRankUp` → 204, then
   both are null and the summary shows `rankReachedDuringRun`.
8. Replay M: task 1 with 0 attempts; three correct answers earn 0/0 each; replay summary `replay` true,
   `xpEarned` 0; HQ total unchanged, M still `COMPLETE`.
9. `PUT /api/child/hq` with `totalXp`/`rank`/`solvedTasks` → 4xx problem+json, totals unchanged. Summary of an
   unfinished mission 409, of an unknown mission 404; replay of an unfinished mission 409.
10. UI: `/` and `/summary?missionId=<M>` render the values above. Backend log holds neither the display name,
    the admin password nor `5,,2`.
11. Clean up with `npm run restore` of the backup from step 1 and remove the smoke PDF from `doucko-storage`.

## Run after the merge (2026-10-07, backend `main` at `9527006`, frontend `main` at `b4b9d81`)

**Result: PASS.** `npm run backup` (`doucko-20261007-183028-675.dump`, schema at V4, no content, 0 files on
`doucko-storage`), then `npm run stack:up` rebuilt the stack from `main` (migrations 1–5). Every request went
through nginx on `127.0.0.1:8081`, scripted with Node `fetch`; child requests carried no cookie. Content was
self-authored and the PDF a blank page (NFR-4). The admin password and the display name came from `.env`,
were compared in memory and never printed.

| Step | Expected | Got | OK |
|---|---|---|---|
| admin: sign in, upload blank PDF | 204, 201 | 204, 201 | yes |
| admin: T1 (2 hints), T2, T3 published; mission M published | created | created | yes |
| HQ before | totalXp 0, rank index 0, nextRank set, pendingRankUp null | totalXp 0, rank 0 Nováček, next 150, solved 0, pending null | yes |
| briefing M xpAvailable | 50 (3 × 10 + 20) | 200 50 | yes |
| T1: 1 hint, correct 1st try | xpAwarded 8, bonus 0 | 200/200 CORRECT_FIRST_TRY xp 8 bonus 0 | yes |
| T2: UNPARSEABLE, wrong, correct | 0, 0, xpAwarded 5 | UNPARSEABLE/0, 0, CORRECT_SECOND_TRY 5 | yes |
| T3: wrong, wrong → revealed, completes M | REVEALED, xpAwarded 1, bonusXp 20, completedMissionIds [M], missionComplete | REVEALED xp 1 bonus 20 ids [M] complete true | yes |
| HQ after M | totalXp 34, solved +3 | totalXp 34, solved 3, rank Nováček | yes |
| summary M | 200, xpEarned 34, firstTryCount 1, taskCount 3, replay false | 200, 34, 1, 3, false | yes |
| briefing M after completion | xpAvailable 0 | 0 | yes |
| admin publishes C of resolved tasks (OQ-28) | totalXp +20 → 54 | 54 | yes |
| unpublish + republish C | pays nothing | 54 | yes |
| mission R (8 tasks) crosses Kadet at completion | last attempt rankUp null, missionComplete | rankUp null, complete true | yes |
| summary R + HQ offer the rank-up | summary rankUp and HQ pendingRankUp = Kadet | Kadet / Kadet, rank Kadet | yes |
| ackRankUp | 204; pendingRankUp null; summary rankUp null, rankReachedDuringRun set | 204; null; null, Kadet | yes |
| replay M: start, 3 correct answers | 200; task 1 with 0 attempts; each 0 XP / 0 bonus; last missionComplete | 200; T1 attempts 0; 0/0, 0/0, 0/0; true | yes |
| replay summary; HQ | replay true, xpEarned 0; totalXp unchanged; M COMPLETE | true, 0; 154 (was 154); COMPLETE | yes |
| PUT /api/child/hq with XP fields | 4xx problem+json, totals unchanged | 405 application/problem+json, 154 | yes |
| summary before completion; unknown mission; replay of incomplete | 409, 404, 409 | 409, 404, 409 | yes |
| UI `/` (built-in browser) | rank, XP, bar, solved, mission states | "Hodnost 2", "Kadet", "154 XP", "4 z 250 XP do hodnosti Strážce", "Vyřešené úlohy: 11", missions 901–903 SPLNĚNO, 904 NOVÁ | yes |
| UI `/summary?missionId=<M>` after the replay | replay summary, 0 XP | "Opakování je hotové!", "Získané XP: 0. Za opakování se XP nepřipisují…", "3 na první pokus", "Úloh v misi: 3" | yes |
| backend log: display name, admin password, `5,,2`; errors | 0, 0, 0; none | 0, 0, 0; none | yes |
| migrations | 1–5 | 1, 2, 3, 4, 5 | yes |

The rank-up celebration itself was not played in the browser on the stack (the pending rank-up was
acknowledged through the API in step 7); the frontend e2e and vision-verify cover it
(`../frontend/docs/qa/vision-report.md`).

**Clean-up.** `npm run restore -- --yes` of the step 1 dump (V4) under the V5 backend: the restore found the
dump behind the running schema and restarted the backend itself (`fix-restore-schema-drift`; backend up
40 s afterwards, migrations 1–5 again, 0 tasks, 0 XP awards); its safety dump is
`doucko-20261007-183601-107.dump`. The tool's console output was lost to a broken pipe filter in the
smoke shell, so the restart is recorded from the container state, not from the tool's lines. The one smoke
PDF was removed from `doucko-storage` (0 files, as before). HQ afterwards: 0 XP, Nováček, 0 solved, no
topics. The stack was left running on `main`.
