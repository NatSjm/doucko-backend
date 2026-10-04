# Slice 6 (implement-authoring-missions-api) — real-stack smoke, task 5.4

- **Status:** NOT RUN, not a pass. The owner runs it after merging the backend and frontend halves of
  slice 6 (the stack from the main checkout uses Compose project `doucko` and port 8081).

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
