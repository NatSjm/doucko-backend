# Slice 7 (implement-practice-flow-api) — real-stack smoke, task 5.4

- **Status:** NOT RUN. Not a pass. The owner runs it after merging the backend and frontend halves of
  slice 7; record the date, both commits and every response status below.

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

## Run after the merge

Not run yet.
