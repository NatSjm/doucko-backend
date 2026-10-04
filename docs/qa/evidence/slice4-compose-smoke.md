# Slice 4 (implement-child-profile-api) — compose smoke, task 5.4: NOT RUN

- **Date:** 2026-10-04 (Europe/Prague)
- **Result:** SKIPPED, not a pass. No request was sent through 127.0.0.1:8081 for this slice.
- **Why:** `docker ps` showed the main checkout's stack running under the same Compose
  project name `doucko` that `npm run stack:up` uses here, and it owns port 8081:

```text
doucko-frontend-1  Up About an hour
doucko-backend-1  Up About an hour (healthy)
doucko-db-1  Up About an hour (healthy)
```

  Starting this worktree's stack would have replaced those containers.
- **What covers the behavior meanwhile (not a substitute for the smoke):** `ProfileSettingsIT`
  (real HTTP against Postgres 16, no cookie or CSRF header) and `ChildProfileStartupIT`
  (whole-application first start and restarts on one database). Evidence:
  `docs/qa/automated-verification-latest.md`.
- **To do when the stack is free:** set `DOUCKO_CHILD_DISPLAY_NAME` in `.env`, `npm run stack:up`,
  then `GET /api/child/profile/settings` (200, null/DIRECT), `PATCH {"secondaryTaskLocale":"uk"}`
  (200), `PATCH {"answerMode":"GUIDED"}` (422), `npm run stack:down`.

## Run after the merge (2026-10-04, branch `slice2-admin-access` at `110581b`)

**Result: PASS.** `npm run stack:up` rebuilt the stack from the merged slices 2–4 code
(`DOUCKO_CHILD_DISPLAY_NAME` set in `.env`). Every request went through nginx on `127.0.0.1:8081`.

| Step | Expected | Got |
|---|---|---|
| `GET /api/child/profile/settings` | 200, `secondaryTaskLocale` null, `answerMode` DIRECT | 200, `{"secondaryTaskLocale":null,"answerMode":"DIRECT"}` |
| `PATCH {"secondaryTaskLocale":"uk"}` | 200 | 200 |
| `PATCH {"answerMode":"GUIDED"}` | 422 | 422 |
| `PATCH {"secondaryTaskLocale":"xx"}` | 400 | 400 |
| `PATCH {"secondaryTaskLocale":null}` (restore) | 200 | 200 |
| `GET /api/admin/session`, no cookie (slice 2 still in place) | 401 | 401 |
| `flyway_schema_history` | V1 applied | `1|t` |
| `select count(*) from child_profile` | 1 | 1 |
| configured display name in `docker compose logs backend` | 0 | 0 |

The stack was left running, as it was before the run.
