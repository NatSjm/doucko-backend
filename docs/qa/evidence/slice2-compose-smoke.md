# Slice 2 — real-stack smoke (backend task 5.4, frontend task 4.4)

- **Date:** 2026-10-04 (Europe/Prague)
- **Stack:** `npm run stack:up` with a throwaway `ADMIN_PASSWORD` in the process environment only
  (not written to `.env`). Everything was reached through the frontend's nginx on `127.0.0.1:8081`.

## API (curl)

| Step | Expected | Got |
|---|---|---|
| `GET /api/admin/session`, no cookie | 401 | 401 |
| `POST /api/admin/session`, wrong password | 401 | 401 |
| `POST /api/admin/session`, right password | 204 + `SESSION` (Path=/api/admin; HttpOnly; SameSite=Strict) + `XSRF-TOKEN` (Path=/; SameSite=Strict) | as expected |
| `GET /api/admin/session` with the cookie | 204 | 204 |
| `DELETE /api/admin/session` without `X-XSRF-TOKEN` | 403 | 403 |
| `DELETE /api/admin/session` with `X-XSRF-TOKEN` | 204 | 204 |
| `GET /api/admin/session` after sign-out | 401 | 401 |
| `POST /api/child/nope` (no CSRF on child paths) | 404 | 404 |
| `docker compose logs backend`, count of the password | 0 | 0 |

## UI (built-in browser)

`http://127.0.0.1:8081/admin` showed "Admin sign-in" with one password
field. A wrong password showed "That password is not correct." The right
password with Enter showed "Admin / You are signed in." A reload kept the
session. "Sign out" returned to the sign-in form.

The stack was stopped with `npm run stack:down`.
