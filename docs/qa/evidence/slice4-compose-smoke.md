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
