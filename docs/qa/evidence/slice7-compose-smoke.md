# Slice 7 (implement-practice-flow-api) — real-stack smoke, task 5.4

- **Status:** PASS after the merge (2026-10-05), see below.

## Click path (API, through 127.0.0.1:8081)

Run `npm run backup` first: the stack holds the owner's data. Migration `V4__practice.sql` is applied on
start. Use self-authored content only (NFR-4); clean up afterwards. Every child request carries no cookie.

1. `npm run stack:up`; sign in as the admin.
2. Upload a self-generated blank PDF; create two tasks of `Desetinná čísla`: T1 with parts a (INTEGER `12`)
   and b (DECIMAL `0.5`) and two hints, T2 with one part a (INTEGER `12`) and a `uk` translation of the
   prompt; publish both; create a mission M with T1, T2 and publish it.
3. `GET /api/child/hq` → 200, `displayName` is the configured name, M listed `NEW` with `taskCount` 2,
   `continueTarget` null.
4. `GET /api/child/missions/{M}/current-task` → 200, T1, `attemptsUsed` 0, `hints.total` 2.
5. `POST …/tasks/{T1}/hints` → 200 `index` 1, `remaining` 1.
6. `POST …/tasks/{T1}/attempts` `{"answers":{"a":"12","b":"3,,4"}}` → 200 `UNPARSEABLE`, only b, `attemptsLeft` 2.
7. `POST …/tasks/{T1}/attempts` `{"answers":{"a":"12","b":"3"}}` → 200 `GRADED`, a `CORRECT`, b `WRONG`,
   `attemptsLeft` 1, `feedback.nextHintIndex` 2, no `0.5` anywhere in the body.
8. `GET /api/child/hq` → M `IN_PROGRESS` 0/2, `continueTarget` = {M, T1}.
9. `POST …/tasks/{T1}/attempts` `{"answers":{"b":"7"}}` → 200 `REVEALED`, `revealedAnswers` a `12`, b `0.5`.
10. `POST …/tasks/{T1}/attempts` again → 409 problem+json.
11. `POST …/tasks/{T1}/reports` `{"reason":"ANSWER_IS_CORRECT"}` → 201.
12. `PATCH /api/child/profile/settings` `{"secondaryTaskLocale":"uk"}`; `GET …/tasks/{T2}` → `prompt.secondary.locale` `uk`;
    set it back to `null`.
13. `POST …/tasks/{T2}/attempts` `{"answers":{"a":"12"}}` → 200 `CORRECT_FIRST_TRY`, `missionComplete` true.
14. `GET /api/child/hq` → M `COMPLETE` 2/2; `GET …/current-task` → 204.
15. Admin: `DELETE /api/admin/tasks/{T1}` after unpublishing M → 409 (used task); `GET /api/admin/tasks/{T1}` →
    `attemptCount` 2, `deletable` false.
16. `docker compose logs backend` holds neither the display name nor `3,,4`.
17. Clean up with `npm run restore` of the backup taken before step 1 (the attempt log is append-only, so the API
    cannot delete practised tasks); `npm run stack:down` only if the stack was not running before.


## Run after the merge (2026-10-05, backend `main` at `a4174e3`, frontend `main` at `1ec25de`)

**Result: PASS.** `npm run backup` (`doucko-20261005-073531-127.dump`, schema at V3), then `npm run stack:up`
rebuilt the stack from `main` (V4 applied). Every request went through nginx on `127.0.0.1:8081`, scripted
with Node `fetch`; child requests carried no cookie. Content was self-authored and the PDF a blank page
(NFR-4). The admin password and the configured display name came from `.env`, were compared in memory
and never printed.

| Step | Expected | Got | OK |
|---|---|---|---|
| admin: sign in, source, T1 (a INTEGER, b DECIMAL, 2 hints), T2 (uk prompt), publish, mission M published | 204, 201s, 200 | as expected | yes |
| HQ: M `NEW`, `taskCount` 2, `continueTarget` null, `displayName` equals the configured name | 200 | 200, as expected | yes |
| current task | 200 T1, 0 attempts, 2 hints | 200 T1 0 2 | yes |
| open hint 1 | 200 index 1, remaining 1 | 200 1 1 | yes |
| a `12`, b `3,,4` | 200 UNPARSEABLE, only b, attemptsLeft 2 | as expected | yes |
| a `12`, b `3` | 200, a CORRECT, b WRONG, attemptsLeft 1, nextHintIndex 2, no `0,5` in the body | as expected | yes |
| HQ | M `IN_PROGRESS`, continueTarget {M, T1} | as expected | yes |
| b `7` (a locked) | 200 REVEALED, revealed a `12`, b `0,5` | as expected | yes |
| another attempt on T1 | 409 problem+json | 409 application/problem+json | yes |
| report ANSWER_IS_CORRECT | 201 | 201 | yes |
| toggle `secondaryTaskLocale` uk, read T2, toggle back | `prompt.secondary.locale` uk | uk | yes |
| T2 `12` | 200 CORRECT_FIRST_TRY, missionComplete true | as expected | yes |
| HQ; current task | M `COMPLETE`; 204 | COMPLETE; 204 | yes |
| unknown mission | 404 | 404 | yes |
| admin unpublishes M; child reads M (FR-12) | 404 | 404 | yes |
| admin deletes T1 (used) | 409 | 409 | yes |
| admin reads T1 | attemptCount 2, deletable false | 2, false | yes |
| backend log: display name, `3,,4`, admin password | 0, 0, 0 | 0, 0, 0 | yes |
| migrations | 1, 2, 3, 4 | 1, 2, 3, 4 | yes |

UI: `/` (HQ) rendered from the stack in the built-in browser with the Czech greeting and, after M was
unpublished, the empty-missions message. The task screen click path was not driven in the browser; the
frontend e2e suite covers it (462 passed on the merged `main`).

**Clean-up and a restore gotcha.** The first attempt of the script read wrong field names (`status`
instead of `state`/`verdict`: a script bug). Restoring the pre-smoke dump to rerun it dropped the V4
tables under the running backend, because the dump predates V4; requests then failed (500/400) until
`docker compose restart backend` re-applied V4. Final state: the pre-smoke dump restored, the backend
restarted (Flyway 1–4), 0 sources, tasks, missions, attempts and reports, the smoke PDFs removed from the
`doucko-storage` volume (the restore does not touch files). The stack was left running.
