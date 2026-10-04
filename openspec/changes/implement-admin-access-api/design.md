# Design — implement-admin-access-api

## Goals / Non-goals

Goals: the store spec `admin-access` behaviors that live in the backend. Sign-in,
session check, sign-out, 401-before-403 ordering, CSRF on admin mutations, the
8 h idle expiry, no lockout, and the startup password policy.

Non-goals: lockout and rate limiting (excluded by the spec), multiple admins,
password change, any content endpoint (slices 5–9 add their handlers behind
this chain). The "Child write is limited to its own practice" scenario needs
attempts and tasks. It is proven in slice 7, and this change proves that every
admin path refuses a client with no session.

## Key decisions

1. **Own module, own chain.** `cz.doucko.admin` registers a `SecurityFilterChain`
   with `securityMatcher("/api/admin/**")` at a higher precedence than the
   shared chain. The shared chain keeps the firewall and problem+json refusals
   and becomes stateless with no CSRF, because nothing outside the admin area
   has a session.
2. **Servlet session, cookie `SESSION`, path `/api/admin`.** The contract names
   the cookie `SESSION`. Path `/api/admin` means the browser never sends the
   admin session to child endpoints (spec: child endpoints neither require nor
   use it). No Spring Session dependency is needed.
3. **Idle expiry on an injectable `Clock`.** `AdminIdleTimeoutFilter` runs before
   the security context is loaded. It keeps the last-activity instant in the
   session and invalidates a session idle for more than 8 h, so the request
   gets 401 and later requests cannot revive it. It runs before `CsrfFilter`, so
   a 403 CSRF refusal still counts as activity. Tomcat's own timeout is also
   8 h, as a backstop. Tests advance a mutable clock (7 h 59 min and
   8 h 1 min boundaries).
4. **401 before 403.** `CsrfFilter` runs before authorization, so its refusal
   goes to the access-denied handler. That handler answers 401 when the caller
   is not authenticated and 403 only for a valid session.
5. **CSRF for an SPA.** `CookieCsrfTokenRepository.withHttpOnlyFalse()` with
   cookie path `/` and SameSite=Strict, and the plain
   `CsrfTokenRequestAttributeHandler`, so the raw cookie value is the header
   value (no BREACH masking: the token is never rendered into HTML). Sign-in is
   excluded and issues a fresh token. Sign-out deletes it.
6. **Sign-in never answers 400.** The generated `signIn` binds an optional JSON
   body. A malformed body, a non-JSON content type and a missing body are
   turned into the same `Invalid credentials` 401 by an advice that applies only
   to `POST /api/admin/session`. Other paths keep the default problem
   responses.
7. **Password check.** The startup value must be 12 or more code points and 72
   or fewer UTF-8 bytes. Otherwise the startup fails with a message that names
   `ADMIN_PASSWORD` and the limit, never the value. Only the BCrypt hash is
   kept in a bean. A submitted value over 72 bytes is refused before BCrypt, so
   a 1 MB body costs no hashing and a 73-byte input sharing the first 72 bytes
   never signs in.

## Module and hexagon placement

- `admin/domain/AdminPasswordPolicy.kt`: pure Kotlin limits and violations.
- `admin/application/AdminCredentials.kt`: hash at startup, `matches(submitted)`.
- `admin/adapter/in/web/`: `AdminSessionController` (implements
  `AdminSessionApi`), `SignInFailureAdvice`, `AdminSecurityConfiguration`,
  `AdminIdleTimeoutFilter`.

## Data model

None. Nothing is stored in the database, and the hash lives in memory.

## Error handling

- 401 `Unauthorized`: no or expired session, on any admin path (problem+json, catalog).
- 401 `Invalid credentials`: every failed sign-in. The bodies are identical
  apart from `instance`, and no session cookie is set.
- 403 `Access denied`: a valid session with a missing or wrong CSRF token.

## Risks and mitigations

- Login fixation: the old session is invalidated and a new one is created on
  sign-in.
- The plain password stays in the process environment. That is accepted, because
  the spec is about the database, logs and responses, and a log-capture IT
  checks those.
