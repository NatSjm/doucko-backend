## 1. Contract and schema

- [x] 1.1 Confirm the `contract/` pin (docs `4c87d67`, API 0.5.1) generates `AdminSessionApi`.
- [x] 1.2 No Flyway migration (nothing is stored).

## 2. Failing tests first (red)

- [x] 2.1 `AdminPasswordPolicyTest` (NFR-16): 11 ASCII, empty and missing values are refused; `привет` is refused; 12 ASCII, `žluťoučkýkůň` and 72 ASCII are accepted; 73 ASCII and 37 × `ž` are refused; messages name `ADMIN_PASSWORD` and the limit, never the value.
- [x] 2.2 `AdminStartupTest` (NFR-16): a context with a bad `ADMIN_PASSWORD` fails to start, and its failure text never contains the value.
- [x] 2.3 `AdminSessionTest` (NFR-1, NFR-15): unauthenticated 401s, sign-in 204 and the session check, identical wrong-password bodies, ignored `username`, empty/`{}`/`""`/null/malformed/non-JSON bodies give 401, a 1 MB body gives 401 and then success, the 73-byte input is refused, 20 failures then success, sign-out with and without CSRF, wrong CSRF, 401 before 403, reads need no CSRF, a child client calling an admin mutation gets 401, and child paths need no CSRF.
- [x] 2.4 `AdminIdleTimeoutTest` (NFR-15): 7 h 59 min is still valid, 8 h 1 min gives 401, activity resets the clock, a 403 CSRF refusal counts as activity, and expired sessions are not revived.
- [x] 2.5 `AdminSessionIT` (NFR-1, NFR-15): over real HTTP, the `SESSION` cookie is HttpOnly, SameSite=Strict, path `/api/admin`; the `XSRF-TOKEN` cookie is readable by JS; the password never appears in logs or responses.
- [x] 2.6 Run the tests and confirm RED on assertions. Evidence: `docs/qa/evidence/slice2-red-run.md`.

## 3. Domain and application (green)

- [x] 3.1 `AdminPasswordPolicy` (domain).
- [x] 3.2 `AdminCredentials` (application): hash at startup, full-value match.

## 4. Adapters (green)

- [x] 4.1 `AdminSecurityConfiguration`, `AdminIdleTimeoutFilter`; shared chain stateless without CSRF.
- [x] 4.2 `AdminSessionController` implements `AdminSessionApi`; `SignInFailureAdvice`.
- [x] 4.3 `application.yaml` session cookie; `compose.yaml` and `.env.example` `ADMIN_PASSWORD`; messages.

## 5. Validation, review, archive

- [x] 5.1 `npm run qa:verify` green.
- [x] 5.2 `npx openspec validate implement-admin-access-api --strict`.
- [ ] 5.3 review-gate workflow; disposition the findings in `review-findings.json`; re-run 5.1.
- [x] 5.4 Smoke: `npm run stack:up` with `ADMIN_PASSWORD` in `.env`; through 8081: `GET /api/admin/session` 401, sign in 204 with `SESSION` and `XSRF-TOKEN`, `GET` 204, `DELETE` without header 403, with header 204, `GET` 401. Evidence: `docs/qa/evidence/slice2-compose-smoke.md`.
- [x] 5.5 Update `docs/current-state.md`.
- [ ] 5.6 Archive; commit with `Slice: implement-admin-access-api` + `Refs: NFR-1, NFR-15, NFR-16`.
