## ADDED Requirements

### Requirement: Admin session endpoints

The backend SHALL implement `getAdminSession`, `signIn` and `signOut` from the
pinned contract behind a dedicated security chain for `/api/admin/**` (store
spec admin-access, NFR-1, NFR-15). Every admin path except `POST
/api/admin/session` SHALL answer 401 problem+json without a valid session,
and a CSRF refusal SHALL be 403 only for a valid session. Sign-in SHALL
answer every failure, including a missing, malformed, non-JSON or oversized
body, with the same 401 `Invalid credentials` problem and no session cookie.

#### Scenario: Sign-in, check and sign-out

- **GIVEN** the backend started with a valid `ADMIN_PASSWORD`
- **WHEN** the client signs in, checks the session, and signs out with the `X-XSRF-TOKEN` header
- **THEN** the answers are 204, 204 and 204, and a following session check is 401

#### Scenario: No session wins over a missing CSRF token

- **WHEN** a client with no session sends `DELETE /api/admin/session` or `PUT /api/admin/missions/{id}/status` without a CSRF token
- **THEN** the answer is 401 problem+json, not 403

### Requirement: Session cookie scope

The session cookie SHALL be named `SESSION`, carry `HttpOnly` and
`SameSite=Strict`, and be scoped to the path `/api/admin`, so child endpoints
never receive it. The `XSRF-TOKEN` cookie SHALL be readable by scripts
(no `HttpOnly`) and scoped to `/`.

#### Scenario: Cookie attributes over HTTP

- **WHEN** a successful sign-in response is inspected on a real port
- **THEN** `SESSION` has `HttpOnly`, `SameSite=Strict` and `Path=/api/admin`, and `XSRF-TOKEN` has `Path=/` and no `HttpOnly`

### Requirement: Idle expiry on the backend clock

A session SHALL expire after 8 hours without a request that carries it,
measured on an injectable clock. A 403 CSRF refusal SHALL count as activity,
and a 401 SHALL NOT revive an expired session.

#### Scenario: Boundaries

- **GIVEN** a signed-in session
- **WHEN** the clock advances 7 h 59 min, or 8 h 1 min
- **THEN** the session check answers 204, or 401 respectively

### Requirement: Startup password policy

The backend SHALL refuse to start when `ADMIN_PASSWORD` is missing, shorter
than 12 code points or longer than 72 UTF-8 bytes. The failure message SHALL
name `ADMIN_PASSWORD` and the limit, never the value (NFR-16).

#### Scenario: Refused value is not printed

- **GIVEN** `ADMIN_PASSWORD` holds 11 ASCII characters
- **WHEN** the application context starts
- **THEN** it fails with a message containing `ADMIN_PASSWORD` and `12`, and the value appears nowhere in the output
