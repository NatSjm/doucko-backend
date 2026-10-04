# Implement admin access (backend)

## Why

Slice 2 of the capability plan (`contract/docs/mvp-capability-plan.md` §4.2).
Store spec: `admin-access`. Every later admin slice (authoring, stats, the
flag queue) sits behind the session this change builds. It owns no FR; it
travels NFR-1 (admin behind a password, child endpoints cannot change
content), NFR-15 (form sign-in, HttpOnly SameSite=Strict cookie, CSRF on
admin mutations, 8 h idle expiry, no lockout) and NFR-16 (password policy at
startup).

## What Changes

- Implements `AdminSessionApi` (`getAdminSession`, `signIn`, `signOut`) from
  the pinned contract.
- New module `cz.doucko.admin`: password policy (domain), credential check
  and startup refusal (application), its own `SecurityFilterChain` for
  `/api/admin/**` (session, CSRF via the `XSRF-TOKEN` cookie and
  `X-XSRF-TOKEN` header, 401 before 403), an idle-timeout filter on an
  injectable `Clock`, and the web adapter.
- `cz.doucko.shared`: the baseline chain no longer owns the admin area; it
  is stateless and CSRF-free for everything outside `/api/admin/**`.
- Configuration: `ADMIN_PASSWORD` (required, env only) replaces the unused
  `DOUCKO_ADMIN_PASSWORD_HASH` in `.env.example`; `compose.yaml` passes it to
  the backend. Session cookie `SESSION`, path `/api/admin`, HttpOnly,
  SameSite=Strict.

## Impact

- Store spec: admin-access · Contract pin: docs `4c87d67` (API 0.5.1), no contract change
- Modules: `cz.doucko.admin` (new), `cz.doucko.shared` · Flyway: none
- Dependencies: slice 1; no new Maven dependencies (BCrypt ships with Spring Security)
- Operators must set `ADMIN_PASSWORD` in `.env` (12 characters to 72 bytes) or the backend refuses to start.
