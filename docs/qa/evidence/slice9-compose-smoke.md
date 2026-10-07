# Slice 9 (implement-progress-stats-api + implement-progress-stats-ui) — real-stack smoke, task 5.4 / 4.4

- **Status:** PASS after the merge (2026-10-08), see below.

## Click path (API, through 127.0.0.1:8081)

Run `npm run backup` first: the stack holds the owner's data. Migration `V6__progress_stats.sql` is applied on
start. Use self-authored content only (NFR-4); clean up afterwards. Every child request carries no cookie.

1. `npm run stack:up`; sign in as the admin; upload a self-generated blank PDF.
2. T1 (DECIMAL, accepted answer wrongly `0.05`), T2 (INTEGER `12`, 1 hint), published; mission M = [T1, T2] published.
3. Child: T1 `0,5` twice → REVEALED (1 XP); report ANSWER_IS_CORRECT with a marker note. T2: open the hint, `12`
   → 8 XP + 20 bonus; report UNCLEAR_PROMPT.
4. Flag queue: both OPEN, oldest first; T1's entry shows the excerpt, reason, note, raw `0,5`, WRONG, attempt 2.
5. Re-evaluate T1 → `changed: false`, still OPEN. Fix T1's accepted answer to `0.5`. Re-evaluate without CSRF → 403;
   with → `changed: true`, +4 XP, CORRECT_SECOND_TRY, FIXED, both verdicts. Again → 409; dismiss FIXED → 409.
6. Dismiss T2's report → DISMISSED; again → 409. Unknown id 404, non-UUID id 400 `reportId`.
7. HQ +4; child task view of T1 agrees with the re-evaluated state. Stats: current week last, +1 session /
   hint / reveal; topic first-try rate counts T1 as 2nd try. `weeks` 12 / absent / 0 / 53 → 12 / 4 / 400 / 400.
   No session → 401.
8. UI `/admin/stats` and `/admin/reports` render the values above. Backend log holds neither the display name,
   the admin password, the note nor the raw input.
9. Clean up with `npm run restore` of the step 1 dump and remove the smoke PDF from the storage volume
   (`doucko_doucko-storage`; compose prefixes the project name).

## Run after the merge (2026-10-08, backend `main` at `e40423d`, frontend `main` at `d08f576`)

**Result: PASS.** `npm run backup` (`doucko-20261007-221930-383.dump`, schema at V5, no content), then
`npm run stack:up` rebuilt the stack from `main` (migrations 1–6). Every request went through nginx on
`127.0.0.1:8081`, scripted with Node `fetch`; child requests carried no cookie. Content was self-authored and the
PDF a blank page (NFR-4). The admin password and the display name came from `.env`, were compared in memory and
never printed.

| Step | Expected | Got | OK |
|---|---|---|---|
| admin: sign in, reference, upload blank PDF | 204/200/201 | 204/200/201 | yes |
| T1, T2 created and published; mission M created and published | 201,201,200,200,201,200 | same | yes |
| child T1: `0,5` twice | REVEALED, xpAwarded 1 | UNRESOLVED → REVEALED, xp 1 | yes |
| child T2: 1 hint, `12` | CORRECT_FIRST_TRY, xp 8, bonus 20, missionComplete | same | yes |
| child reports on T1 (with note) and T2 | 201, 201 | 201, 201 | yes |
| HQ after M | totalXp +29 | 0 → 29 | yes |
| queue `?status=OPEN` | T1 then T2 | T1, T2 | yes |
| T1 entry | excerpt, ANSWER_IS_CORRECT, note, raw `0,5`, WRONG, attempt 2, no re-evaluation | same | yes |
| queue `?status=fixed` | 400 problem+json, field `status` | same | yes |
| re-evaluate before the fix | changed false, xp 0, OPEN | same | yes |
| fix T1's accepted answer (published, in a published mission) | 200 | 200 | yes |
| re-evaluate without CSRF header | 403 | 403 | yes |
| re-evaluate after the fix | changed true, xp 4, bonus 0, CORRECT_SECOND_TRY, FIXED, re-evaluated CORRECT, original WRONG | same | yes |
| re-evaluate / dismiss the FIXED report | 409, 409 problem+json | same | yes |
| dismiss T2's report; again | 200 DISMISSED; 409 | same | yes |
| unknown id; non-UUID id | 404; 400 `reportId` | same | yes |
| filters FIXED / DISMISSED | T1 in FIXED, T2 in DISMISSED | yes / yes | yes |
| HQ after re-evaluation | +4, pendingRankUp null | 29 → 33, null | yes |
| child task view T1 | CORRECT_SECOND_TRY, lastFeedback verdict CORRECT | same | yes |
| stats `weeks=5` | 5 weeks, current week (2026-10-05) last | same | yes |
| current week | sessions ≥ 1, hints +1, revealed +1 | sessions 0→1, hints 0→1, revealed 0→1, tasksPerSession [2] | yes |
| topic first-try rate | resolved +2, firstTry +1 | 0→2, 0→1 | yes |
| metricMet | false (no history) | false | yes |
| `weeks` 12; absent; 0; 53 | 12; 4; 400 `weeks`; 400 | same | yes |
| no session: stats (invalid weeks) / reports | 401 / 401 | 401 / 401 | yes |
| UI `/admin/stats` (headless Chromium, 1280×720) | verdict, 5 weeks, this week first, topic rate | "NOT MET", short weeks 09-07…09-28, "2026-10-05 THIS WEEK 1 0 2 1 1", "Přirozená čísla 50% 1 of 2 first try"; 12 weeks → 12 rows | yes |
| UI `/admin/reports` | both reports, raw input, both verdicts | FIXED entry with note, raw `0,5`, "WRONG" / "CORRECT on 2026-10-08 00:24"; DISMISSED entry, no actions | yes |
| display name on the admin pages | absent | absent | yes |
| backend log: display name, admin password, note, `"0,5"`; errors | 0, 0, 0, 0; none | 0, 0, 0, 0; no ERROR / exception | yes |

Observation (not a failure): the queue's answer-part table names the part by its key (`p1`), not its label
(`a)`), because `LoggedAttempt` carries keys only. A label would need a contract change.

**Clean-up.** `npm run restore -- --yes` of the step 1 dump (V5) under the V6 backend: the restore restarted the
backend itself and Flyway migrated the restored schema to V6; its safety dump is
`doucko-20261007-222440-732.dump`. Afterwards: 0 tasks, 0 XP awards, 0 re-evaluations; HQ 0 XP, Nováček,
0 solved, no topics. The stack was left running on `main`. **Open:** the smoke PDF
(`sources/238e3537-….pdf`, 329 bytes) is still on `doucko_doucko-storage`, and an empty volume `doucko-storage`
was created by mistake while checking the volume before the run; both are left for the owner to remove.
