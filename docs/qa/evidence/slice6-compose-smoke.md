# Slice 6 (implement-authoring-missions-api) — real-stack smoke, task 5.4

- **Status:** PASS after the merge (2026-10-05), see below.

## Click path (API, through 127.0.0.1:8081)

1. `npm run stack:up`; sign in as the admin.
2. Create two tasks of topic `Desetinná čísla` and publish both (`PUT /api/admin/tasks/{id}/status`).
3. `POST /api/admin/missions` with that topic, position 1 and both task ids → 201, `status: DRAFT`.
4. `PUT /api/admin/missions/{id}/status` `PUBLISHED` → 200.
5. Set one task to `DRAFT` → 409 problem+json, `detail` names "Desetinná čísla, mission 1", `conflicts` holds the mission id.
6. `DELETE` that task → 409 (task in a published mission).
7. Unpublish the mission → 200; `DELETE` the mission → 204; both tasks still listed by `GET /api/admin/tasks`.
8. `npm run stack:down`.

Record the date, the commit and each response status here when it has run.

## Run after the merge (2026-10-05, backend `main` at `fa333ad`, frontend `main` at `945dee8`)

**Result: PASS.** `npm run backup` first (`doucko-20261005-045234-495.dump`), then `npm run stack:up`
rebuilt the stack from `main` (migration V3 applied). Every request went through nginx on
`127.0.0.1:8081`, scripted with Node `fetch`. The PDF was a self-generated blank page (NFR-4). The
admin password came from `.env` and was never printed.

| Step | Expected | Got | OK |
|---|---|---|---|
| sign in | 204 | 204 | yes |
| upload source | 201 | 201 | yes |
| create 3 tasks (2 in `Desetinná čísla`, 1 in another topic) | 201 ×3 | 201 ×3 | yes |
| bulk publish the tasks | 200 | 200 | yes |
| create mission (position 1, two tasks) | 201 DRAFT | 201 DRAFT | yes |
| duplicate position refused | 400 `position` | 400 `position` | yes |
| task of another topic refused | 400 `taskIds[0]` | 400 `taskIds[0]` | yes |
| 51 tasks refused | 400 `taskIds` | 400 `taskIds` | yes |
| publish mission | 200 PUBLISHED | 200 PUBLISHED | yes |
| task back to draft refused | 409, detail names the mission, `conflicts` = [mission] | 409 "This task is in a published mission: Desetinná čísla, mission 1. Unpublish that mission first.", [mission id] | yes |
| delete task in a published mission refused | 409 | 409 | yes |
| empty a published mission refused | 409 | 409 | yes |
| unpublish mission | 200 | 200 | yes |
| delete mission | 204 | 204 | yes |
| tasks survive the mission delete | 3 | 3 | yes |
| publish an empty mission refused | 409 | 409 | yes |
| clean up (mission, 3 tasks, source) | 204 ×5 | 204 ×5 | yes |
| migrations applied | 1, 2, 3 | 1, 2, 3 | yes |
| admin password in backend log | 0 | 0 | yes |
| sign out | 204 | 204 | yes |

The new UI routes `/admin/missions`, `/admin/missions/edit` and `/admin/sources/edit` are served by
nginx (200). The UI click path was not driven in a browser this time; the frontend e2e suite covers
it (321 passed on the merged `main`, chromium, msedge and firefox). After clean-up the database holds
0 tasks, 0 missions and 0 sources. The stack was left running.
